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



val supportCategories = attendeeCaseCategories
// Match Python/Pydantic str.strip(), including Unicode separators and U+001C..U+001F.
fun supportTrim(value:String):String = value.trim {it.isWhitespace() || it=='\u0085' || it in '\u001C'..'\u001F'}
fun supportTextLength(value:String):Int = value.indices.count {i ->
    i==0 || !value[i].isLowSurrogate() || !value[i-1].isHighSurrogate()
}
data class SupportState(
    val eventId: Int? = null, val orderId: Int? = null, val ticketId: Int? = null,
    val category: String = "event_information", val subject: String = "", val message: String = "",
    val sending: Boolean = false, val errors: Set<String> = emptySet(), val error: String? = null,
    val unknown: Boolean = false, val previousUnknown: Boolean = false,
    val created: SupportCase? = null, val waitingForLogin: Boolean = false,
)
class SupportViewModel(client: HttpClient, requests: SessionOrdersRepository, private val session: SessionStore,
    baseUrl: String = apiBaseUrl) : ViewModel() {
    private val repository = SupportCasesRepository(client, requests, baseUrl)
    private val mutable = MutableStateFlow(SupportState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var authenticated = session.tokens.value != null
    private var sequence = 0

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
            category = if (orderId != null || ticketId != null) "ticket_delivery" else "event_information",
            waitingForLogin = session.tokens.value == null)
    }
    fun close() { sequence++; mutable.value = SupportState() }
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

        mutable.value = next.copy(errors = next.errors - key, error = null,
            unknown = if (changed) false else current.unknown,
            previousUnknown = current.previousUnknown || changed && current.unknown)
    }

    fun submit() {
        val current = mutable.value; val id = current.eventId ?: return
        if (current.sending || current.created != null || current.unknown || current.previousUnknown) return
        val errors = buildSet {
            if (current.category !in supportCategories) add("category")
            if (supportTextLength(current.subject.trim()) !in 1..200) add("subject")
            if (supportTextLength(current.message.trim()) !in 1..5000) add("message")
        }
        if (errors.isNotEmpty()) { mutable.value = current.copy(errors = errors); return }
        if (session.tokens.value == null) { mutable.value = current.copy(waitingForLogin = true); return }
        val payload = SupportCaseCreate(category=current.category,subject=current.subject.trim(),message=current.message.trim(),
            eventId=if(current.orderId==null && current.ticketId==null) id else null,
            orderId=if(current.ticketId==null) current.orderId else null,ticketId=current.ticketId)
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
fun SupportPage(vm: SupportViewModel, isAuthenticated: Boolean, onOpenRequest: (Int) -> Unit = {}, onCheckCases: () -> Unit = {}, onLogin: () -> Unit) {
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
            Text(supportStatusLabel(result.status))
            Text(result.createdAt)
            Button(onClick = { onOpenRequest(result.id) }) { Text(stringResource(Res.string.support_open)) }
        } else {
            Text(stringResource(Res.string.support_category))
            supportCategories.forEach { category ->
                FilterChip(selected = state.category == category, onClick = { vm.update("category", category) }, enabled = !state.sending,
                    label = { Text(stringResource(when(category) {
                        "event_information" -> Res.string.support_category_event
                        "ticket_delivery" -> Res.string.support_category_ticket
                        "payment" -> Res.string.support_category_payment
                        "refund" -> Res.string.support_category_refund
                        "seating" -> Res.string.support_category_seating
                        "check_in" -> Res.string.support_category_checkin
                        "account" -> Res.string.support_category_account
                        else -> Res.string.support_category_technical
                    })) })
            }
            if("category" in state.errors) Text(stringResource(Res.string.support_case_category_error),color=MaterialTheme.colorScheme.error)
            listOf("subject" to Res.string.support_subject, "message" to Res.string.support_message).forEach { (key,label) ->
                OutlinedTextField(value = if (key == "subject") state.subject else state.message, onValueChange = { vm.update(key,it) },
                    label = { Text(stringResource(label)) }, enabled = !state.sending, isError = key in state.errors,
                    modifier = Modifier.fillMaxWidth(), singleLine = key == "subject", minLines = if (key == "message") 4 else 1)
                if (key in state.errors) Text(stringResource(if(key == "subject") Res.string.support_subject_error else Res.string.support_message_error), color = MaterialTheme.colorScheme.error)
            }
            if (state.error != null) { Text(stringResource(Res.string.support_error), color = MaterialTheme.colorScheme.error); Text(state.error!!) }
            if (state.unknown || state.previousUnknown) {
                Text(stringResource(Res.string.support_case_unknown))
                Button(onClick=onCheckCases) { Text(stringResource(Res.string.support_history)) }
            }
            if (state.sending) CircularProgressIndicator()
            if (isAuthenticated) Button(onClick = vm::submit, enabled = !state.sending && !state.unknown && !state.previousUnknown) { Text(stringResource(Res.string.support_send)) }
            else Button(onClick = onLogin) { Text(stringResource(Res.string.auth_login_button)) }
        }
    }
}
