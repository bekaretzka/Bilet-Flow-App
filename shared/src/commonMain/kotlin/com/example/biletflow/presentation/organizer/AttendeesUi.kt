package com.example.biletflow.presentation.organizer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.events.formatEventDate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Serializable
data class EventAttendee(
    @SerialName("ticket_id") val ticketId: Int,
    @SerialName("attendee_name") val attendeeName: String? = null,
    @SerialName("ticket_type_name") val ticketTypeName: String? = null,
    val status: String,
    @SerialName("order_id") val orderId: Int,
    @SerialName("buyer_email") val buyerEmail: String? = null,
    @SerialName("created_at") val createdAt: String,
)

data class AttendeesState(
    val eventId: Int? = null, val title: String = "", val loading: Boolean = false,
    val attendees: List<EventAttendee> = emptyList(), val query: String = "",
    val error: Boolean = false, val denied: Boolean = false,
) {
    val filtered: List<EventAttendee> get() {
        val search = query.trim()
        return attendees.filter {
            search.isEmpty() || it.attendeeName?.contains(search, ignoreCase = true) == true ||
                it.buyerEmail?.contains(search, ignoreCase = true) == true
        }
    }
}

class AttendeesViewModel(
    private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl,
) : ViewModel() {
    private val mutable = MutableStateFlow(AttendeesState())
    val state = mutable.asStateFlow()
    private var sequence = 0
    private var generation = session.generation
    init {
        viewModelScope.launch {
            session.tokens.collect {
                if (generation != session.generation) { generation = session.generation; close() }
            }
        }
    }
    fun open(id: Int, title: String) {
        sequence++
        mutable.value = AttendeesState(eventId = id, title = title)
        reload()
    }
    fun close() { sequence++; mutable.value = AttendeesState() }
    fun search(query: String) { mutable.value = mutable.value.copy(query = query) }
    fun reload() {
        val current = mutable.value
        val id = current.eventId ?: return
        if (current.loading) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(loading = true, error = false, denied = false, attendees = emptyList())
        viewModelScope.launch {
            try {
                val attendees = requests.request { token ->
                    val response = client.get("$baseUrl/events/$id/attendees") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<List<EventAttendee>>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Attendees request failed")
                    }
                }
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(loading = false, attendees = attendees)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(loading = false, error = true,
                    denied = api?.statusCode == 403 || api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError", "EventAccessDeniedError"))
            }
        }
    }
}

@Composable
fun AttendeesPage(vm: AttendeesViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::close) { Text(stringResource(Res.string.ticket_create_back)) }
        Text(stringResource(Res.string.attendees_title), style = MaterialTheme.typography.headlineLarge)
        Text(state.title)
        TextButton(onClick = vm::reload, enabled = !state.loading) { Text(stringResource(Res.string.my_events_refresh)) }
        OutlinedTextField(value = state.query, onValueChange = vm::search,
            label = { Text(stringResource(Res.string.attendees_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        when {
            state.loading -> CircularProgressIndicator()
            state.error -> {
                Text(stringResource(Res.string.attendees_error))
                Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
            }
            state.attendees.isEmpty() -> Text(stringResource(Res.string.attendees_empty))
            state.filtered.isEmpty() -> Text(stringResource(Res.string.attendees_no_matches))
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.filtered) { attendee ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            attendee.attendeeName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            attendee.buyerEmail?.let { Text(it) }
                            attendee.ticketTypeName?.let { Text(it) }
                            Text(stringResource(when (attendee.status) {
                                "valid" -> Res.string.attendees_valid
                                "checked_in" -> Res.string.attendees_checked_in
                                "cancelled" -> Res.string.attendees_cancelled
                                "refunded" -> Res.string.attendees_refunded
                                else -> Res.string.ticket_unavailable
                            }))
                            Text("${stringResource(Res.string.attendees_ticket)} #${attendee.ticketId} · ${stringResource(Res.string.attendees_order)} #${attendee.orderId}")
                            Text("${stringResource(Res.string.attendees_created)}: ${formatEventDate(attendee.createdAt)}")
                        }
                    }
                }
            }
        }
    }
}
