package com.example.biletflow.presentation.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.datetime.Instant
import org.jetbrains.compose.resources.stringResource

data class SupportHistoryState(
    val open: Boolean = false, val fromCreation: Boolean = false,
    val items: List<SupportRequest> = emptyList(), val nextCursor: String? = null,
    val loading: Boolean = false, val error: String? = null,
    val selectedId: Int? = null, val request: SupportRequest? = null,
    val detailLoading: Boolean = false, val detailError: String? = null,
    val messages: List<SupportMessage> = emptyList(), val olderCursor: String? = null,
    val messagesLoading: Boolean = false, val messagesError: String? = null,
)

class SupportHistoryViewModel(client: HttpClient, requests: SessionOrdersRepository,
    private val session: SessionStore, baseUrl: String = apiBaseUrl) : ViewModel() {
    private val repository = SupportRepository(client, requests, baseUrl)
    private val mutable = MutableStateFlow(SupportHistoryState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var authenticated = session.tokens.value != null
    private var epoch = 0
    private var listSequence = 0
    private var detailSequence = 0
    private var messageSequence = 0
    private var listAppend = false
    private var messageAppend = false
    private val listCursors = mutableSetOf<String>()
    private val messageCursors = mutableSetOf<String>()
    init { viewModelScope.launch { session.tokens.collect { tokens ->
        if (generation != session.generation) {
            val guestLogin = !authenticated && tokens != null && mutable.value.open && mutable.value.selectedId == null
            generation = session.generation
            if (guestLogin) reload() else close()
        }
        authenticated = tokens != null
    } } }
    fun open() { close(); mutable.value = SupportHistoryState(open = true); reload() }
    fun close() { epoch++; listSequence++; detailSequence++; messageSequence++; listCursors.clear(); messageCursors.clear(); mutable.value = SupportHistoryState() }
    fun back() {
        if (mutable.value.fromCreation || mutable.value.selectedId == null) close()
        else { detailSequence++; messageSequence++; mutable.value = mutable.value.copy(selectedId = null, request = null,
            messages = emptyList(), olderCursor = null, detailLoading = false, messagesLoading = false, detailError = null, messagesError = null) }
    }
    private fun message(error: Exception) = (error as? OrdersApiException)?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network"
    fun reload() = load(false)
    fun more() = load(true)
    fun retryList() = load(listAppend)
    private fun load(append: Boolean) {
        val current = mutable.value
        if (!current.open || current.loading || session.tokens.value == null) return
        val cursor = if (append) current.nextCursor ?: return else null
        listAppend = append
        val expected = session.generation; val context = epoch; val sequence = ++listSequence
        if (!append) listCursors.clear()
        mutable.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val page = repository.list(cursor)
                if (expected != session.generation || context != epoch || sequence != listSequence) return@launch
                check(page.nextCursor == null || page.nextCursor != cursor && page.nextCursor !in listCursors)
                cursor?.let { listCursors.add(it) }
                mutable.value = mutable.value.copy(loading = false, items =
                    ((if (append) mutable.value.items else emptyList()) + page.items).distinctBy { it.id }, nextCursor = page.nextCursor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && context == epoch && sequence == listSequence)
                    mutable.value = mutable.value.copy(loading = false, error = message(error))
            }
        }
    }
    fun select(id: Int, fromCreation: Boolean = false) {
        detailSequence++; messageSequence++; messageCursors.clear()
        mutable.value = mutable.value.copy(open = true, selectedId = id, fromCreation = fromCreation,
            request = null, messages = emptyList(), olderCursor = null, detailLoading = false, messagesLoading = false,
            detailError = null, messagesError = null)
        refreshThread()
    }
    fun refreshThread() { readDetails(); loadMessages(false) }
    fun readDetails() {
        val id = mutable.value.selectedId ?: return
        if (mutable.value.detailLoading || session.tokens.value == null) return
        val expected = session.generation; val context = epoch; val sequence = ++detailSequence
        mutable.value = mutable.value.copy(detailLoading = true, detailError = null)
        viewModelScope.launch {
            try {
                val result = repository.get(id)
                if (expected == session.generation && context == epoch && sequence == detailSequence)
                    mutable.value = mutable.value.copy(request = result, detailLoading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && context == epoch && sequence == detailSequence)
                    mutable.value = mutable.value.copy(detailLoading = false, detailError = message(error))
            }
        }
    }
    fun older() = loadMessages(true)
    fun retryMessages() = loadMessages(false)
    fun retryMessagePage() = loadMessages(messageAppend)
    private fun loadMessages(append: Boolean) {
        val current = mutable.value; val id = current.selectedId ?: return
        if (current.messagesLoading || session.tokens.value == null) return
        val cursor = if (append) current.olderCursor ?: return else null
        messageAppend = append
        val expected = session.generation; val context = epoch; val sequence = ++messageSequence
        if (!append) messageCursors.clear()
        mutable.value = current.copy(messagesLoading = true, messagesError = null)
        viewModelScope.launch {
            try {
                val page = repository.messages(id,cursor)
                if (expected != session.generation || context != epoch || sequence != messageSequence) return@launch
                check(page.nextCursor == null || page.nextCursor != cursor && page.nextCursor !in messageCursors)
                cursor?.let { messageCursors.add(it) }
                // A refresh merges the latest page with older messages already read.
                val merged = (page.items + mutable.value.messages).distinctBy { it.id }
                    .sortedWith(compareByDescending<SupportMessage> { Instant.parse(it.createdAt) }.thenByDescending { it.id })
                mutable.value = mutable.value.copy(messagesLoading = false, messages = merged,
                    olderCursor = if (append || current.messages.isEmpty()) page.nextCursor else current.olderCursor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && context == epoch && sequence == messageSequence)
                    mutable.value = mutable.value.copy(messagesLoading = false, messagesError = message(error))
            }
        }
    }
}

@Composable
private fun SupportContext(request: SupportRequest) {
    Text(request.eventTitle ?: "${stringResource(Res.string.support_event)} #${request.eventId}")
    request.orderId?.let { Text("${stringResource(Res.string.order_result_number)} #$it") }
    request.ticketId?.let { Text("${stringResource(Res.string.attendees_ticket)} #$it") }
}
@Composable
private fun SupportReadError(error: String?, retry: () -> Unit) {
    if (error != null) { Text(stringResource(Res.string.support_read_error),color = MaterialTheme.colorScheme.error)
        Text(error); TextButton(onClick = retry) { Text(stringResource(Res.string.events_retry)) } }
}
@Composable
fun SupportHistoryPage(vm: SupportHistoryViewModel, authenticated: Boolean, onLogin: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::back) { Text(stringResource(Res.string.support_back)) }
        Text(stringResource(Res.string.support_history),style = MaterialTheme.typography.headlineMedium)
        if (!authenticated) {
            Text(stringResource(Res.string.support_login_prompt))
            Button(onClick = onLogin) { Text(stringResource(Res.string.auth_login_button)) }
        } else if (state.selectedId == null) {
            TextButton(onClick = vm::reload,enabled = !state.loading) { Text(stringResource(Res.string.support_refresh)) }
            SupportReadError(state.error, vm::retryList)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loading && state.error == null && state.items.isEmpty()) Text(stringResource(Res.string.support_empty))
            LazyColumn(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items,key = { it.id }) { request ->
                    Card(onClick = { vm.select(request.id) },modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("#${request.id} ${request.subject}",style = MaterialTheme.typography.titleMedium)
                            Text(if(request.status == "open") stringResource(Res.string.support_status_open) else request.status)
                            Text(request.updatedAt); SupportContext(request)
                        }
                    }
                }
                if (state.nextCursor != null) item { TextButton(onClick = vm::more, enabled = !state.loading) { Text(stringResource(Res.string.support_more)) } }
            }
        } else key(state.selectedId) {
            TextButton(onClick = vm::refreshThread,enabled = !state.detailLoading && !state.messagesLoading) { Text(stringResource(Res.string.support_refresh)) }
            if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            SupportReadError(state.detailError,vm::readDetails)
            state.request?.let { request ->
                Text("#${request.id} ${request.subject}",style = MaterialTheme.typography.titleLarge)
                Text(if(request.status == "open") stringResource(Res.string.support_status_open) else request.status)
                SupportContext(request)
            }
            SupportReadError(state.messagesError,vm::retryMessagePage)
            if (state.messagesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.messagesLoading && state.messagesError == null && state.messages.isEmpty()) Text(stringResource(Res.string.support_messages_empty))
            // Stable IDs + reverse layout keep the visible message anchored when older pages are appended.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state = rememberLazyListState(),reverseLayout = true,verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages,key = { it.id }) { message ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(message.authorName ?: stringResource(when(message.authorRole) { "visitor" -> Res.string.support_author_visitor; "organizer" -> Res.string.support_author_organizer; else -> Res.string.support_author_other }),style = MaterialTheme.typography.labelLarge)
                        Text(message.text); Text(message.createdAt,style = MaterialTheme.typography.labelSmall)
                    } }
                }
                if(state.olderCursor != null) item(key = "older") { TextButton(onClick = vm::older,enabled = !state.messagesLoading) { Text(stringResource(Res.string.support_older)) } }
            }
        }
    }
}
