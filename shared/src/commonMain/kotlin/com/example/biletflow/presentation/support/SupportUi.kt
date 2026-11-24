package com.example.biletflow.presentation.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

val supportCategories = listOf("event_question", "ticket_issue", "payment_issue", "other")
fun supportTextLength(value: String): Int = value.indices.count { i ->
    i == 0 || !value[i].isLowSurrogate() || !value[i - 1].isHighSurrogate()
}
data class SupportState(
    val eventId: Int? = null, val orderId: Int? = null, val ticketId: Int? = null,
    val category: String = "event_question", val subject: String = "", val message: String = "",
    val sending: Boolean = false, val errors: Set<String> = emptySet(), val error: String? = null,
    val unknown: Boolean = false, val previousUnknown: Boolean = false,
    val created: SupportRequest? = null, val waitingForLogin: Boolean = false,
)
class SupportViewModel(client: HttpClient, requests: SessionOrdersRepository, private val session: SessionStore,
    baseUrl: String = apiBaseUrl) : ViewModel() {
    private val repository = SupportRepository(client, requests, baseUrl)
    private val mutable = MutableStateFlow(SupportState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var authenticated = session.tokens.value != null
    private var sequence = 0
    private var snapshot: SupportCreate? = null
    init { viewModelScope.launch { session.tokens.collect { tokens ->
        if (generation != session.generation) {
            val guestLogin = !authenticated && tokens != null && mutable.value.waitingForLogin
            generation = session.generation
            if (guestLogin) mutable.value = mutable.value.copy(waitingForLogin = false) else close()
        }
        authenticated = tokens != null
    } } }
    fun open(eventId: Int, orderId: Int? = null, ticketId: Int? = null) {
        close(); mutable.value = SupportState(eventId, orderId, ticketId,
            category = if (orderId != null || ticketId != null) "ticket_issue" else "event_question",
            waitingForLogin = session.tokens.value == null)
    }
    fun close() { sequence++; snapshot = null; mutable.value = SupportState() }
    fun update(key: String, value: String) {
        val current = mutable.value
        if (current.sending || current.created != null) return
        val next = when (key) {
            "category" -> current.copy(category = value)
            "subject" -> current.copy(subject = value)
            "message" -> current.copy(message = value)
            else -> return
        }
        val changed = next.category != current.category || next.subject.trim() != current.subject.trim() || next.message.trim() != current.message.trim()
        if (changed) snapshot = null
        mutable.value = next.copy(errors = next.errors - key, error = null,
            unknown = if (changed) false else current.unknown,
            previousUnknown = current.previousUnknown || changed && current.unknown)
    }
    @OptIn(ExperimentalUuidApi::class)
    fun submit() {
        val current = mutable.value; val id = current.eventId ?: return
        if (current.sending || current.created != null) return
        val errors = buildSet {
            if (current.category !in supportCategories) add("category")
            if (supportTextLength(current.subject.trim()) !in 3..200) add("subject")
            if (supportTextLength(current.message.trim()) !in 1..5000) add("message")
        }
        if (errors.isNotEmpty()) { mutable.value = current.copy(errors = errors); return }
        if (session.tokens.value == null) { mutable.value = current.copy(waitingForLogin = true); return }
        val payload = snapshot ?: SupportCreate(id, current.orderId, current.ticketId, current.category,
            current.subject.trim(), current.message.trim(), Uuid.random().toString()).also { snapshot = it }
        val expected = session.generation; val requestId = ++sequence
        mutable.value = current.copy(sending = true, errors = emptySet(), error = null)
        viewModelScope.launch {
            try {
                val result = repository.create(payload)
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(sending = false, created = result, unknown = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? SupportApiException
                val unknown = api == null && error !is OrdersApiException || api != null && api.statusCode >= 500
                mutable.value = mutable.value.copy(sending = false, unknown = unknown,
                    errors = api?.fields.orEmpty(), error = api?.let { "HTTP ${it.statusCode}: ${it.code ?: it.message}" }
                        ?: (error as? OrdersApiException)?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network")
            }
        }
    }
}

@Composable
fun SupportPage(vm: SupportViewModel, isAuthenticated: Boolean, onOpenRequest: (Int) -> Unit = {}, onLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::close, enabled = !state.sending) { Text(stringResource(Res.string.support_back)) }
        Text(stringResource(Res.string.support_contact), style = MaterialTheme.typography.headlineLarge)
        Text("${stringResource(Res.string.support_event)} #${state.eventId}")
        state.orderId?.let { Text("${stringResource(Res.string.order_result_number)} #$it") }
        state.ticketId?.let { Text("${stringResource(Res.string.attendees_ticket)} #$it") }
        val result = state.created
        if (result != null) {
            Text(stringResource(Res.string.support_created), style = MaterialTheme.typography.titleLarge)
            Text("#${result.id}")
            Text(if (result.status == "open") stringResource(Res.string.support_status_open) else result.status)
            Text(result.createdAt)
            Button(onClick = { onOpenRequest(result.id) }) { Text(stringResource(Res.string.support_open)) }
        } else {
            Text(stringResource(Res.string.support_category))
            supportCategories.forEach { category ->
                FilterChip(selected = state.category == category, onClick = { vm.update("category", category) }, enabled = !state.sending,
                    label = { Text(stringResource(when(category) {
                        "event_question" -> Res.string.support_category_event
                        "ticket_issue" -> Res.string.support_category_ticket
                        "payment_issue" -> Res.string.support_category_payment
                        else -> Res.string.support_category_other
                    })) })
            }
            listOf("subject" to Res.string.support_subject, "message" to Res.string.support_message).forEach { (key,label) ->
                OutlinedTextField(value = if (key == "subject") state.subject else state.message, onValueChange = { vm.update(key,it) },
                    label = { Text(stringResource(label)) }, enabled = !state.sending, isError = key in state.errors,
                    modifier = Modifier.fillMaxWidth(), singleLine = key == "subject", minLines = if (key == "message") 4 else 1)
                if (key in state.errors) Text(stringResource(if(key == "subject") Res.string.support_subject_error else Res.string.support_message_error), color = MaterialTheme.colorScheme.error)
            }
            if (state.error != null) { Text(stringResource(Res.string.support_error), color = MaterialTheme.colorScheme.error); Text(state.error!!) }
            if (state.unknown || state.previousUnknown) Text(stringResource(Res.string.support_unknown))
            if (state.sending) CircularProgressIndicator()
            if (isAuthenticated) Button(onClick = vm::submit, enabled = !state.sending) { Text(stringResource(Res.string.support_send)) }
            else Button(onClick = onLogin) { Text(stringResource(Res.string.auth_login_button)) }
        }
    }
}
