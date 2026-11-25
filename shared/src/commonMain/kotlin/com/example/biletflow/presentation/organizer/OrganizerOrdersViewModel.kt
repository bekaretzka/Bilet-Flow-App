package com.example.biletflow.presentation.organizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OrganizerOrdersState(
    val eventId: Int? = null, val title: String = "", val orders: List<OrganizerOrder> = emptyList(),
    val loading: Boolean = false, val nextOffset: Int = 0, val total: Int = 0, val error: String? = null,
    val query: String = "", val status: String? = null,
    val selectedId: Int? = null, val order: OrganizerOrder? = null, val detailLoading: Boolean = false,
    val detailError: String? = null, val denied: Boolean = false,
    val confirming: Boolean = false, val reason: String = "", val invalidReason: Boolean = false,
    val refundBusy: Boolean = false, val refundError: String? = null, val refundSucceeded: Boolean = false,
    val unknown: Set<Int> = emptySet(),
)

class OrganizerOrdersViewModel(client: HttpClient, requests: SessionOrdersRepository,
    private val session: SessionStore, baseUrl: String = apiBaseUrl) : ViewModel() {
    private val repository = OrganizerOrdersRepository(client, requests, baseUrl)
    private val mutable = MutableStateFlow(OrganizerOrdersState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var epoch = 0
    private var listSequence = 0
    private var detailSequence = 0
    private var listJob: Job? = null
    init { viewModelScope.launch { session.tokens.collect {
        if (generation != session.generation) { generation = session.generation; close() }
    } } }
    fun open(eventId: Int, title: String) {
        close(); mutable.value = OrganizerOrdersState(eventId = eventId, title = title); reload()
    }
    fun close() { epoch++; listSequence++; detailSequence++; listJob?.cancel(); mutable.value = OrganizerOrdersState() }
    fun back() {
        if (mutable.value.refundBusy) return
        if (mutable.value.confirming) { dismissRefund(); return }
        if (mutable.value.selectedId == null) close()
        else {
            detailSequence++
            mutable.value = mutable.value.copy(selectedId = null, order = null, detailLoading = false, detailError = null,
                reason = "", invalidReason = false, refundError = null, refundSucceeded = false)
        }
    }
    private fun message(error: Exception) = (error as? OrdersApiException)?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network"
    private fun denied(error: Exception) = (error as? OrdersApiException)?.let {
        it.statusCode == 403 || it.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError", "EventAccessDeniedError")
    } == true
    fun reload() = loadPage(false)
    fun more() = loadPage(true)
    fun search(value: String) = filter(value, mutable.value.status)
    fun status(value: String?) = filter(mutable.value.query, value)
    private fun filter(query: String, status: String?) {
        if(mutable.value.refundBusy) return
        listJob?.cancel(); listSequence++
        mutable.value=mutable.value.copy(query=query,status=status,loading=false,orders=emptyList(),nextOffset=0,total=0)
        loadPage(false)
    }
    private fun loadPage(append: Boolean) {
        val current = mutable.value
        val eventId = current.eventId ?: return
        if (current.loading || current.refundBusy) return
        val offset = if (append) current.nextOffset.takeIf { it < current.total } ?: return else 0
        val expected = session.generation; val context = epoch; val requestId = ++listSequence
        mutable.value = current.copy(loading = true, error = null,
            orders = if (append) current.orders else emptyList(), nextOffset = offset)
        listJob=viewModelScope.launch {
            try {
                val page = repository.list(eventId, offset, current.status, current.query)
                if (expected != session.generation || context != epoch || requestId != listSequence) return@launch
                mutable.value = mutable.value.copy(loading = false, orders =
                    ((if (append) mutable.value.orders else emptyList()) + page.items).distinctBy { it.id },
                    total=page.total, nextOffset=if(page.items.isEmpty()) page.total else offset+page.items.size)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && context == epoch && requestId == listSequence)
                    mutable.value = mutable.value.copy(loading = false, error = message(error), denied = denied(error))
            }
        }
    }
    fun select(orderId: Int) {
        if (mutable.value.refundBusy || mutable.value.eventId == null) return
        mutable.value = mutable.value.copy(selectedId = orderId, order = null, reason = "", confirming = false,
            invalidReason = false, refundError = null, refundSucceeded = false)
        checkOrder()
    }
    fun checkOrder() = readOrder(false)
    private fun readOrder(keepRefundError: Boolean) {
        val current = mutable.value
        val eventId = current.eventId ?: return; val id = current.selectedId ?: return
        if (current.refundBusy) return
        val expected = session.generation; val context = epoch; val requestId = ++detailSequence
        mutable.value = current.copy(detailLoading = true, detailError = null, confirming = false)
        viewModelScope.launch {
            try {
                val order = repository.get(eventId, id)
                if (expected != session.generation || context != epoch || requestId != detailSequence) return@launch
                mutable.value = mutable.value.copy(detailLoading = false, order = order, unknown = mutable.value.unknown - id,
                    orders = mutable.value.orders.map { if (it.id == id) order else it },
                    refundError = if (keepRefundError) mutable.value.refundError else null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && context == epoch && requestId == detailSequence)
                    mutable.value = mutable.value.copy(detailLoading = false, detailError = message(error), denied = denied(error))
            }
        }
    }
    fun askRefund() {
        val current = mutable.value
        if (!current.refundBusy && !current.detailLoading && current.detailError == null &&
            current.order?.refundable == true && current.selectedId !in current.unknown)
            mutable.value = current.copy(confirming = true, reason = "", invalidReason = false)
    }
    fun dismissRefund() { if (!mutable.value.refundBusy) mutable.value = mutable.value.copy(confirming = false, reason = "", invalidReason = false) }
    fun reason(value: String) { if (!mutable.value.refundBusy) mutable.value = mutable.value.copy(reason = value, invalidReason = false) }
    fun confirmRefund() {
        val current = mutable.value; val order = current.order ?: return
        if (!current.confirming || current.refundBusy || current.detailLoading || !order.refundable || order.id in current.unknown) return
        val reason = current.reason.trim()
        if (reason.length !in 3..500) { mutable.value = current.copy(invalidReason = true); return }
        val expected = session.generation; val context = epoch; val requestId = ++detailSequence
        listSequence++
        mutable.value = current.copy(refundBusy = true, confirming = false, refundError = null, loading = false)
        viewModelScope.launch {
            try {
                val response = repository.refund(requireNotNull(current.eventId), order.id, reason)
                if (expected != session.generation || context != epoch || requestId != detailSequence) return@launch
                val result=order.copy(status=response.status, paidAt=response.paidAt,refundedAt=response.refundedAt,cancelledAt=response.cancelledAt,totalAmount=response.totalAmount)
                mutable.value = mutable.value.copy(refundBusy = false, order = result, refundSucceeded = true,
                    orders = mutable.value.orders.map { if (it.id == result.id) result else it }, reason = "")
                reload()
                readOrder(false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || context != epoch || requestId != detailSequence) return@launch
                val api = error as? OrdersApiException
                val unknown = api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable"
                mutable.value = mutable.value.copy(refundBusy = false, refundError = api?.errorCode ?: message(error),
                    unknown = if (unknown) mutable.value.unknown + order.id else mutable.value.unknown, denied = denied(error))
                if (api?.statusCode == 409) readOrder(true)
            }
        }
    }
}
