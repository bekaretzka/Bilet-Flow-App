package com.example.biletflow.presentation.organizer

import com.example.biletflow.core.designsystem.Card
import com.example.biletflow.core.designsystem.TextButton
import com.example.biletflow.core.designsystem.Button
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
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Serializable
data class EventStaff(
    @SerialName("user_id") val userId: Int,
    val email: String,
    @SerialName("created_at") val createdAt: String,
)

data class EventStaffState(
    val eventId: Int? = null, val title: String = "", val staff: List<EventStaff> = emptyList(),
    val loading: Boolean = false, val loaded: Boolean = false, val busy: Boolean = false,
    val email: String = "", val emailError: String? = null, val error: Boolean = false,
    val actionError: String? = null, val unknown: Boolean = false, val denied: Boolean = false,
    val removing: EventStaff? = null,
)

class EventStaffViewModel(
    private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl,
) : ViewModel() {
    private val mutable = MutableStateFlow(EventStaffState())
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
    fun open(id: Int, title: String) { sequence++; mutable.value = EventStaffState(eventId = id, title = title); reload() }
    fun close() { sequence++; mutable.value = EventStaffState() }
    private fun canChange() = mutable.value.let { it.eventId != null && it.loaded && !it.loading && !it.busy && !it.unknown }
    fun email(value: String) {
        if (!mutable.value.busy && !mutable.value.unknown)
            mutable.value = mutable.value.copy(email = value, emailError = null, actionError = null)
    }
    fun askRemove(staff: EventStaff) {
        if (canChange() && staff in mutable.value.staff) mutable.value = mutable.value.copy(removing = staff)
    }
    fun dismissRemove() { mutable.value = mutable.value.copy(removing = null) }
    fun confirmRemove() {
        val staff = mutable.value.removing ?: return
        if (canChange()) change(staff.userId)
    }
    fun add() {
        if (!canChange()) return
        val email = mutable.value.email.trim().lowercase()
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) {
            mutable.value = mutable.value.copy(emailError = "invalid")
            return
        }
        mutable.value = mutable.value.copy(email = email)
        change(null)
    }
    private fun change(removeUserId: Int?) {
        val current = mutable.value
        val id = current.eventId ?: return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(busy = true, removing = null, actionError = null, emailError = null)
        viewModelScope.launch {
            var fieldError: String? = null
            try {
                requests.request { token ->
                    val response = if (removeUserId != null) client.delete("$baseUrl/events/$id/staff/$removeUserId") { bearerAuth(token) }
                    else client.post("$baseUrl/events/$id/staff") {
                        bearerAuth(token); contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("email", current.email) })
                    }
                    val expectedStatus = if (removeUserId == null) 201 else 204
                    if (response.status.value != expectedStatus) {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        fieldError = organizerFieldErrors(payload)["email"]
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Staff change failed")
                    }
                    // DELETE 204 intentionally has no body.
                    if (removeUserId == null) response.body<EventStaff>()
                }
                if (expected != session.generation || requestId != sequence) return@launch
                mutable.value = mutable.value.copy(busy = false, email = if (removeUserId == null) "" else current.email)
                reload()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(busy = false, emailError = fieldError,
                    actionError = api?.errorCode ?: "unknown",
                    unknown = api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable",
                    denied = accessDenied(api))
                if (api?.errorCode in listOf("StaffAlreadyAssignedError", "StaffAssignmentNotFoundError")) reload()
            }
        }
    }
    fun reload() {
        val current = mutable.value
        val id = current.eventId ?: return
        if (current.loading || current.busy) return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(loading = true, loaded = false, staff = emptyList(), error = false, removing = null)
        viewModelScope.launch {
            try {
                val staff = requests.request { token ->
                    val response = client.get("$baseUrl/events/$id/staff") { bearerAuth(token) }
                    if (response.status.value == 200) response.body<List<EventStaff>>() else {
                        val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        throw OrdersApiException(response.status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull, "Staff list failed")
                    }
                }
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(staff = staff, loading = false, loaded = true, unknown = false,
                        actionError = if (current.unknown) null else current.actionError)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                mutable.value = mutable.value.copy(loading = false, error = true, denied = accessDenied(error as? OrdersApiException))
            }
        }
    }
    private fun accessDenied(api: OrdersApiException?) = api?.statusCode == 403 ||
        api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError", "EventAccessDeniedError")
}

@Composable
fun EventStaffPage(vm: EventStaffViewModel) {
    val state by vm.state.collectAsState()
    val enabled = state.loaded && !state.loading && !state.busy && !state.unknown
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::close) { Text(stringResource(Res.string.ticket_create_back)) }
        Text(stringResource(Res.string.staff_title), style = MaterialTheme.typography.headlineLarge)
        Text(state.title)
        Text(stringResource(Res.string.staff_account_required))
        OutlinedTextField(value = state.email, onValueChange = vm::email,
            label = { Text(stringResource(Res.string.staff_email)) }, singleLine = true,
            enabled = !state.busy && !state.unknown, isError = state.emailError != null,
            supportingText = { state.emailError?.let { Text(if (it == "invalid") stringResource(Res.string.staff_invalid_email) else it) } },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = vm::add, enabled = enabled) { Text(stringResource(Res.string.staff_add)) }
        TextButton(onClick = vm::reload, enabled = !state.loading && !state.busy) {
            Text(stringResource(if (state.unknown) Res.string.payment_check_status else Res.string.my_events_refresh))
        }
        if (state.busy) CircularProgressIndicator()
        if (state.unknown) Text(stringResource(Res.string.staff_unknown))
        else state.actionError?.let { code ->
            Text(stringResource(when (code) {
                "UserNotFoundError" -> Res.string.staff_user_missing
                "StaffAlreadyAssignedError" -> Res.string.staff_duplicate
                "StaffAssignmentNotFoundError" -> Res.string.staff_assignment_missing
                else -> Res.string.staff_change_error
            }), color = MaterialTheme.colorScheme.error)
        }
        when {
            state.loading -> CircularProgressIndicator()
            state.error -> {
                Text(stringResource(Res.string.staff_load_error))
                Button(onClick = vm::reload) { Text(stringResource(Res.string.events_retry)) }
            }
            state.loaded && state.staff.isEmpty() -> Text(stringResource(Res.string.staff_empty))
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.staff, key = { it.userId }) { staff ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(staff.email, style = MaterialTheme.typography.titleMedium)
                            Text("${stringResource(Res.string.staff_user_id)}: ${staff.userId}")
                            Text("${stringResource(Res.string.staff_assigned_at)}: ${formatEventDate(staff.createdAt)}")
                            TextButton(onClick = { vm.askRemove(staff) }, enabled = enabled) { Text(stringResource(Res.string.staff_remove)) }
                        }
                    }
                }
            }
        }
    }
    state.removing?.let { staff ->
        AlertDialog(onDismissRequest = vm::dismissRemove,
            title = { Text(stringResource(Res.string.staff_remove)) },
            text = { Text("${stringResource(Res.string.staff_remove_confirm)} ${staff.email}") },
            confirmButton = { TextButton(onClick = vm::confirmRemove) { Text(stringResource(Res.string.staff_remove)) } },
            dismissButton = { TextButton(onClick = vm::dismissRemove) { Text(stringResource(Res.string.auth_close)) } })
    }
}
