package com.example.biletflow.presentation.organizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class MyEventsState(val open: Boolean = false, val loading: Boolean = false,
    val events: List<Event> = emptyList(), val filter: String? = null,
    val error: Boolean = false, val accessDenied: Boolean = false, val selectedId: Int? = null,
    val changingStatus: Boolean = false, val statusUnknown: Boolean = false,
    val statusError: String? = null, val statusSuccess: Boolean = false, val catalogDirty: Boolean = false)

class MyEventsViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(MyEventsState())
    val state = mutable.asStateFlow()
    private var sequence = 0
    private var generation = session.generation
    init { viewModelScope.launch { session.tokens.collect {
        if (generation != session.generation) {
            generation = session.generation
            close()
        }
    } } }
    fun open() { mutable.value = MyEventsState(open = true, catalogDirty = mutable.value.catalogDirty); reload() }
    fun close() { sequence++; mutable.value = MyEventsState(catalogDirty = mutable.value.catalogDirty) }
    fun filter(status: String?) { mutable.value = mutable.value.copy(filter = status) }
    fun select(id: Int) { mutable.value = mutable.value.copy(selectedId = id, statusSuccess = false, statusError = null); reload() }
    fun backToList() {
        if (mutable.value.changingStatus) return
        mutable.value = mutable.value.copy(selectedId = null)
        reload()
    }
    fun updated(event: Event) {
        mutable.value = mutable.value.copy(events = mutable.value.events.filterNot { it.id == event.id } + event)
    }
    fun catalogRefreshed() { mutable.value = mutable.value.copy(catalogDirty = false) }
    fun changeStatus(publish: Boolean) = changeStatus(if(publish) "publish" else "unpublish")
    fun cancelEvent() = changeStatus("cancel")
    private fun changeStatus(action: String) {
        val current = mutable.value
        val id = current.selectedId ?: return
        if (current.loading || current.changingStatus || current.statusUnknown) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(changingStatus = true, statusError = null, statusSuccess = false, catalogDirty = true)
        viewModelScope.launch {
            try {
                val event = requests.request { token ->
                    val response = client.patch("$baseUrl/events/$id/$action") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<Event>() else {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        val code = body?.get("error")?.jsonPrimitive?.content
                        val details = listOfNotNull(body?.get("message"), body?.get("detail"), body?.get("fields")).joinToString("\n")
                        throw OrdersApiException(response.status.value, code, details)
                    }
                }
                if (expected == session.generation && requestId == sequence && mutable.value.selectedId == id) {
                    updated(event)
                    mutable.value = mutable.value.copy(changingStatus = false, statusSuccess = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence || mutable.value.selectedId != id) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(changingStatus = false,
                    statusUnknown = api == null, statusError = api?.message ?: "unknown",
                    accessDenied = api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError", "EventAccessDeniedError"))
                if (api?.errorCode == "InvalidEventStatusError") reload()
            }
        }
    }
    fun reload() {
        if (mutable.value.loading || !mutable.value.open) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = mutable.value.copy(loading = true, error = false, events = emptyList())
        viewModelScope.launch {
            try {
                val events = requests.request { token ->
                    val response = client.get("$baseUrl/events/me") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<List<Event>>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.content, "My events request failed")
                    }
                }
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(loading = false, events = events, statusUnknown = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val denied = (error as? OrdersApiException)?.errorCode in
                    listOf("OrganizerNotFoundError", "OrganizerNotApprovedError")
                mutable.value = mutable.value.copy(loading = false, error = !denied, accessDenied = denied, open = !denied)
            }
        }
    }
}
