package com.example.biletflow.presentation.organizer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.events.formatEventDate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Serializable
data class CheckInStats(
    val registered: Int,
    @SerialName("checked_in") val checkedIn: Int,
    @SerialName("not_checked_in") val notCheckedIn: Int,
    val cancelled: Int, val refunded: Int,
)

@Serializable
data class ScanTicket(
    val id: Int, val code: String,
    @SerialName("attendee_name") val attendeeName: String?,
    @SerialName("ticket_type_name") val ticketTypeName: String?,
    val status: String,
    @SerialName("checked_in_at") val checkedInAt: String? = null,
)
@Serializable
data class CheckInResponse(val result: String, val message: String, val ticket: ScanTicket? = null)

data class CheckInOverviewState(
    val event: Event? = null, val stats: CheckInStats? = null,
    val loadingStats: Boolean = false, val statsError: Boolean = false,
    val query: String = "", val invalidQuery: Boolean = false,
    val tickets: List<ScanTicket> = emptyList(), val searching: Boolean = false,
    val searched: Boolean = false, val searchError: Boolean = false, val denied: Boolean = false,
    val confirming: ScanTicket? = null, val checkingIn: Boolean = false,
    val checkInResult: CheckInResponse? = null, val checkInError: Boolean = false,
    val unknownCode: String? = null, val verifiedAfterUnknown: Boolean = false,
    val confirmingUndo: Boolean = false, val undoOperation: Boolean = false,
    val undoResult: ScanTicket? = null, val undoAbsent: Boolean = false,
    val scannerOpen: Boolean = false, val scannerForeground: Boolean = false, val scannerEpoch: Long = 0,
    /** A Campaign QR (promo link) was scanned: it is never sent to check-in. */
    val campaignQr: Boolean = false,
)

class CheckInOverviewViewModel(
    private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl,
) : ViewModel() {
    private val mutable = MutableStateFlow(CheckInOverviewState())
    val state = mutable.asStateFlow()
    private var statsSequence = 0
    private var searchSequence = 0
    private var checkInSequence = 0
    private var scannerEpoch = 0L
    private var frameGate = QrFrameGate()
    private val frameClock = kotlin.time.TimeSource.Monotonic.markNow()
    private var generation = session.generation
    init {
        viewModelScope.launch { session.tokens.collect {
            if (generation != session.generation) { generation = session.generation; close() }
        } }
    }
    fun open(event: Event) { close(); mutable.value = CheckInOverviewState(event = event); reloadStats() }
    fun close() { statsSequence++; searchSequence++; checkInSequence++; scannerEpoch++; mutable.value = CheckInOverviewState() }
    fun openScanner() {
        val current = mutable.value
        if (current.event == null || current.checkingIn || current.unknownCode != null || current.denied) return
        frameGate = QrFrameGate()
        mutable.value = current.copy(scannerOpen = true, scannerEpoch = ++scannerEpoch,
            checkInResult = null, checkInError = false, undoResult = null, undoAbsent = false, verifiedAfterUnknown = false)
    }
    fun closeScanner() { mutable.value = mutable.value.copy(scannerOpen = false, scannerForeground = false, scannerEpoch = ++scannerEpoch) }
    fun scannerForeground(active: Boolean) {
        if (!mutable.value.scannerOpen) return
        frameGate.resume()
        mutable.value = mutable.value.copy(scannerForeground = active, scannerEpoch = ++scannerEpoch)
    }
    fun scannerFrame(epoch: Long, codes: List<String>) {
        val current = mutable.value
        if (epoch != current.scannerEpoch || !current.scannerOpen || !current.scannerForeground || current.checkingIn ||
            current.checkInResult != null || current.checkInError || current.unknownCode != null || current.verifiedAfterUnknown || current.denied || current.campaignQr) return
        val code = frameGate.accept(codes, frameClock.elapsedNow().inWholeMilliseconds) ?: return
        if (com.example.biletflow.data.repositories.campaignTokenFromLink(code) != null) { mutable.value = current.copy(campaignQr = true); return }
        performEntryChange(false, code)
    }
    fun nextScannedTicket() {
        if (!mutable.value.scannerOpen || mutable.value.checkingIn || mutable.value.unknownCode != null) return
        frameGate.resume()
        mutable.value = mutable.value.copy(scannerEpoch = ++scannerEpoch, checkInResult = null, checkInError = false, verifiedAfterUnknown = false, campaignQr = false)
    }
    fun query(value: String) {
        if (mutable.value.checkingIn) return
        searchSequence++
        mutable.value = mutable.value.copy(query = value, invalidQuery = false, tickets = emptyList(), searching = false, searched = false, searchError = false)
    }
    private fun denied(error: Exception): Boolean {
        val api = error as? OrdersApiException
        return api?.statusCode == 403 || api?.errorCode in listOf("NotEventStaffError", "EventNotFoundError")
    }
    private fun clearAfterAccessFailure() {
        statsSequence++; searchSequence++; checkInSequence++
        mutable.value = CheckInOverviewState(event = mutable.value.event, denied = true)
    }
    fun askCheckIn(ticket: ScanTicket) {
        val current = mutable.value
        if (!current.checkingIn && !current.searching && current.unknownCode == null && !current.denied && ticket in current.tickets)
            mutable.value = current.copy(confirming = ticket, confirmingUndo = false)
    }
    fun askUndo(ticket: ScanTicket) {
        val current = mutable.value
        if (ticket.status == "checked_in" && ticket in current.tickets && !current.checkingIn && !current.searching && current.unknownCode == null && !current.denied)
            mutable.value = current.copy(confirming = ticket, confirmingUndo = true)
    }
    fun dismissCheckIn() { mutable.value = mutable.value.copy(confirming = null, confirmingUndo = false) }
    fun verifyUnknown() {
        val code = mutable.value.unknownCode ?: return
        if (mutable.value.searching || mutable.value.checkingIn) return
        query(code)
        reloadStats()
        runSearch(code)
    }
    fun confirmCheckIn() { if (!mutable.value.confirmingUndo) performEntryChange(false) }
    fun confirmUndo() { if (mutable.value.confirmingUndo) performEntryChange(true) }
    private fun performEntryChange(undo: Boolean, scannedCode: String? = null) {
        val current = mutable.value
        val ticket = current.confirming
        if (scannedCode == null && ticket == null) return
        val code = scannedCode ?: requireNotNull(ticket).code
        val id = current.event?.id ?: return
        if (current.checkingIn || current.unknownCode != null || current.denied) return
        val expected = session.generation
        val sequence = ++checkInSequence
        // Invalidate reads made before the mutation; the post-operation reads will replace them.
        statsSequence++; searchSequence++
        mutable.value = current.copy(confirming = null, confirmingUndo = false, checkingIn = true, checkInResult = null, checkInError = false,
            undoOperation = undo, undoResult = null, undoAbsent = false,
            loadingStats = false, searching = false, verifiedAfterUnknown = false)
        viewModelScope.launch {
            try {
                val result = requests.request { token ->
                    val response = client.post("$baseUrl/events/$id/check-in${if (undo) "/undo" else ""}") {
                        bearerAuth(token); contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { if (undo) put("ticket_id", requireNotNull(ticket).id) else put("code", code) })
                    }
                    if (response.status.value == 200) {
                        if (undo) {
                            val updated = response.body<ScanTicket>()
                            require(updated.id == requireNotNull(ticket).id)
                            CheckInResponse("undo", "", updated)
                        } else response.body<CheckInResponse>()
                    } else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Check-in failed")
                    }
                }
                if (expected != session.generation || sequence != checkInSequence) return@launch
                mutable.value = mutable.value.copy(checkingIn = false,
                    checkInResult = if (undo) null else result, undoResult = if (undo) result.ticket else null)
                reloadStats(); search()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || sequence != checkInSequence) return@launch
                if (denied(error)) { clearAfterAccessFailure(); return@launch }
                val api = error as? OrdersApiException
                if (undo && api?.errorCode == "TicketNotCheckedInError") {
                    mutable.value = mutable.value.copy(checkingIn = false, undoAbsent = true)
                    reloadStats(); search()
                    return@launch
                }
                val unknown = api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable"
                mutable.value = mutable.value.copy(checkingIn = false, checkInError = !unknown, unknownCode = if (unknown) code else null)
            }
        }
    }
    private suspend inline fun <reified T> get(id: Int, route: String, query: String? = null): T = requests.request { token ->
        val response = client.get("$baseUrl/events/$id/check-in/$route") {
            bearerAuth(token)
            if (query != null) parameter("q", query)
        }
        if (response.status.value == 200) response.body<T>() else {
            val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Check-in request failed")
        }
    }
    fun reloadStats() {
        val id = mutable.value.event?.id ?: return
        if (mutable.value.loadingStats || mutable.value.checkingIn) return
        val expected = session.generation
        val sequence = ++statsSequence
        mutable.value = mutable.value.copy(loadingStats = true, statsError = false, stats = null)
        viewModelScope.launch {
            try {
                val stats = get<CheckInStats>(id, "stats")
                if (expected == session.generation && sequence == statsSequence)
                    mutable.value = mutable.value.copy(loadingStats = false, stats = stats)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || sequence != statsSequence) return@launch
                if (denied(error)) clearAfterAccessFailure()
                else mutable.value = mutable.value.copy(loadingStats = false, statsError = true)
            }
        }
    }
    fun search() = runSearch()
    private fun runSearch(exactCode: String? = null) {
        val current = mutable.value
        val id = current.event?.id ?: return
        if (current.searching || current.checkingIn) return
        val query = exactCode ?: current.query.trim()
        if (query.length !in 2..100) {
            mutable.value = current.copy(invalidQuery = true)
            return
        }
        val expected = session.generation
        val sequence = ++searchSequence
        mutable.value = current.copy(query = query, searching = true, invalidQuery = false, searchError = false, searched = false, tickets = emptyList())
        viewModelScope.launch {
            try {
                val tickets = get<List<ScanTicket>>(id, "search", query)
                if (expected == session.generation && sequence == searchSequence) {
                    val checkedUnknown = mutable.value.unknownCode == query && tickets.any { it.code == query }
                    mutable.value = mutable.value.copy(searching = false, searched = true, tickets = tickets,
                        unknownCode = if (checkedUnknown) null else mutable.value.unknownCode,
                        verifiedAfterUnknown = checkedUnknown || mutable.value.verifiedAfterUnknown)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || sequence != searchSequence) return@launch
                if (denied(error)) clearAfterAccessFailure()
                else mutable.value = mutable.value.copy(searching = false, searchError = true)
            }
        }
    }
}

@Composable
fun CheckInOverviewPage(vm: CheckInOverviewViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.ticket_create_back)) }
            Text(state.event?.title.orEmpty(), style = MaterialTheme.typography.headlineLarge)
            if (state.event?.status == "cancelled") Text(stringResource(Res.string.staff_events_cancelled), color = MaterialTheme.colorScheme.error)
            Button(onClick = vm::openScanner, enabled = !state.checkingIn && state.unknownCode == null) { Text(stringResource(Res.string.camera_scan)) }
            if (state.checkingIn) CircularProgressIndicator()
            state.checkInResult?.let { result ->
                Text(stringResource(checkInResultLabel(result.result)), style = MaterialTheme.typography.titleMedium,
                    color = if (result.result == "checked_in") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(result.message)
                result.ticket?.let { Text("${it.attendeeName.orEmpty()} · ${it.ticketTypeName.orEmpty()} · #${it.id}") }
            }
            state.undoResult?.let { ticket ->
                Text(stringResource(if (ticket.status == "valid" && ticket.checkedInAt == null) Res.string.undo_success else Res.string.undo_server_result), style = MaterialTheme.typography.titleMedium)
                Text("${ticket.attendeeName.orEmpty()} · #${ticket.id}")
                Text(when (ticket.status) {
                    "valid" -> stringResource(Res.string.attendees_valid)
                    "checked_in" -> stringResource(Res.string.attendees_checked_in)
                    "cancelled" -> stringResource(Res.string.attendees_cancelled)
                    "refunded" -> stringResource(Res.string.attendees_refunded)
                    else -> ticket.status
                })
                ticket.checkedInAt?.let { Text(formatEventDate(it)) }
            }
            if (state.undoAbsent) Text(stringResource(Res.string.undo_absent))
            if (state.checkInError) Text(stringResource(if (state.undoOperation) Res.string.undo_error else Res.string.check_action_error), color = MaterialTheme.colorScheme.error)
            if (state.unknownCode != null) {
                Text(stringResource(if (state.undoOperation) Res.string.undo_unknown else Res.string.check_action_unknown))
                Button(onClick = vm::verifyUnknown, enabled = !state.searching) { Text(stringResource(Res.string.payment_check_status)) }
            }
            if (state.verifiedAfterUnknown) Text(stringResource(Res.string.check_verified_notice))
        }
        item {
            Text(stringResource(Res.string.check_stats_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = vm::reloadStats, enabled = !state.loadingStats && !state.checkingIn) { Text(stringResource(Res.string.my_events_refresh)) }
            when {
                state.loadingStats -> CircularProgressIndicator()
                state.statsError -> {
                    Text(stringResource(Res.string.check_stats_error))
                    Button(onClick = vm::reloadStats) { Text(stringResource(Res.string.events_retry)) }
                }
                else -> state.stats?.let { stats ->
                    listOf(Res.string.check_registered to stats.registered, Res.string.check_checked_in to stats.checkedIn,
                        Res.string.check_not_checked_in to stats.notCheckedIn, Res.string.attendees_cancelled to stats.cancelled,
                        Res.string.attendees_refunded to stats.refunded).forEach { (label, value) -> Text("${stringResource(label)}: $value") }
                }
            }
        }
        item {
            OutlinedTextField(value = state.query, onValueChange = vm::query, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.check_search_hint)) }, singleLine = true, isError = state.invalidQuery, enabled = !state.checkingIn,
                supportingText = { Text(stringResource(if (state.invalidQuery) Res.string.check_query_length else Res.string.check_search_rules)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search() }))
            Button(onClick = vm::search, enabled = !state.searching && !state.checkingIn) { Text(stringResource(Res.string.check_find)) }
            if (state.searching) CircularProgressIndicator()
            if (state.searchError) {
                Text(stringResource(Res.string.check_search_error))
                Button(onClick = vm::search) { Text(stringResource(Res.string.events_retry)) }
            }
            if (state.searched && state.tickets.isEmpty()) Text(stringResource(Res.string.check_search_empty))
        }
        items(state.tickets, key = { it.id }) { ticket ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${stringResource(Res.string.attendees_ticket)} #${ticket.id}", style = MaterialTheme.typography.titleMedium)
                    ticket.attendeeName?.let { Text(it) }; ticket.ticketTypeName?.let { Text(it) }
                    Text(ticket.code)
                    Text(when (ticket.status) {
                        "valid" -> stringResource(Res.string.attendees_valid)
                        "checked_in" -> stringResource(Res.string.attendees_checked_in)
                        "cancelled" -> stringResource(Res.string.attendees_cancelled)
                        "refunded" -> stringResource(Res.string.attendees_refunded)
                        else -> ticket.status
                    })
                    ticket.checkedInAt?.let { Text("${stringResource(Res.string.check_checked_at)}: ${formatEventDate(it)}") }
                    Button(onClick = { vm.askCheckIn(ticket) }, enabled = !state.checkingIn && !state.searching && state.unknownCode == null) {
                        Text(stringResource(Res.string.check_action))
                    }
                    if (ticket.status == "checked_in") TextButton(onClick = { vm.askUndo(ticket) }, enabled = !state.checkingIn && !state.searching && state.unknownCode == null) {
                        Text(stringResource(Res.string.undo_action))
                    }
                }
            }
        }
    }
    state.confirming?.let { ticket ->
        AlertDialog(onDismissRequest = vm::dismissCheckIn,
            title = { Text(stringResource(if (state.confirmingUndo) Res.string.undo_action else Res.string.check_action)) },
            text = { Text("${stringResource(if (state.confirmingUndo) Res.string.undo_confirm else Res.string.check_confirm)}\n${ticket.attendeeName.orEmpty()}\n${ticket.ticketTypeName.orEmpty()}\n#${ticket.id}\n${ticket.code}") },
            confirmButton = { TextButton(onClick = if (state.confirmingUndo) vm::confirmUndo else vm::confirmCheckIn) { Text(stringResource(if (state.confirmingUndo) Res.string.undo_action else Res.string.check_action)) } },
            dismissButton = { TextButton(onClick = vm::dismissCheckIn) { Text(stringResource(Res.string.auth_close)) } })
    }
}

internal fun checkInResultLabel(result: String) = when (result) {
    "checked_in" -> Res.string.check_result_success
    "already_checked_in" -> Res.string.check_result_already
    "invalid" -> Res.string.check_result_invalid
    "wrong_event" -> Res.string.check_result_wrong_event
    "cancelled" -> Res.string.check_result_cancelled
    "refunded" -> Res.string.check_result_refunded
    "event_cancelled" -> Res.string.check_result_event_cancelled
    else -> Res.string.check_result_unknown
}
