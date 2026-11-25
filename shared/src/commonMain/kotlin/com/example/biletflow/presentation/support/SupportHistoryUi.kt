package com.example.biletflow.presentation.support

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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
    val open: Boolean = false, val fromCreation: Boolean = false, val eventId: Int? = null, val eventTitle: String = "",
    val items: List<SupportRequest> = emptyList(), val nextCursor: String? = null,
    val loading: Boolean = false, val error: String? = null,
    val selectedId: Int? = null, val request: SupportRequest? = null,
    val detailLoading: Boolean = false, val detailError: String? = null,
    val messages: List<SupportMessage> = emptyList(), val olderCursor: String? = null,
    val messagesLoading: Boolean = false, val messagesError: String? = null,
    val statusBusy: Boolean = false, val statusUnknown: Boolean = false, val statusError: String? = null,
    val draft: String = "", val sending: Boolean = false, val invalidDraft: Boolean = false,
    val sendError: String? = null, val unknownSend: Boolean = false, val previousUnknown: Boolean = false,
    val newLoading: Boolean = false, val newError: String? = null, val unread: Int = 0, val scrollSignal: Int = 0,
)

class SupportHistoryViewModel(client: HttpClient, requests: SessionOrdersRepository,
    private val session: SessionStore, baseUrl: String = apiBaseUrl) : ViewModel() {
    private val repository = SupportRepository(client, requests, baseUrl)
    private val mutable = MutableStateFlow(SupportHistoryState())
    val state = mutable.asStateFlow()
    private var pollJob: Job? = null
    private var updatesJob: Job? = null
    private var sendSequence = 0
    private var sendSnapshot: SupportMessageCreate? = null
    private var highWater: Int? = null
    private var latestVisible = true
    private var foreground = false
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
    fun openEvent(id: Int, title: String) { close(); mutable.value = SupportHistoryState(open = true,eventId = id,eventTitle = title); reload() }
    fun changeStatus(target: String) {
        val current = mutable.value; val request = current.request ?: return
        if(current.statusBusy || current.statusUnknown || current.sending || !request.canManageStatus || target !in supportTransitions(request.status)) return
        val expected = session.generation; val context = epoch; val sequence = ++detailSequence
        mutable.value = current.copy(statusBusy = true, statusError = null, detailLoading = false)
        viewModelScope.launch {
            try {
                val result = repository.changeStatus(request.id, SupportStatusChange(target,canonicalSupportStatus(request.status)))
                require(result.eventId == request.eventId)
                if(expected != session.generation || context != epoch || sequence != detailSequence) return@launch
                mutable.value = mutable.value.copy(statusBusy = false, request = result,
                    items = mutable.value.items.map { if(it.id == result.id) result else it })
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected != session.generation || context != epoch || sequence != detailSequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(statusBusy = false,statusError = message(error),
                    statusUnknown = api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable")
                if(api?.statusCode == 409) readDetails()
            }
        }
    }
    fun open() { close(); mutable.value = SupportHistoryState(open = true); reload() }
    fun visibleLatest(value: Boolean) {
        latestVisible = value
        if(value && mutable.value.unread != 0) mutable.value = mutable.value.copy(unread = 0)
    }
    private fun merge(incoming: List<SupportMessage>, notify: Boolean = true) {
        val current = mutable.value
        val added = incoming.count { item -> current.messages.none { it.id == item.id } }
        mutable.value = current.copy(messages = (incoming + current.messages).distinctBy { it.id }
            .sortedWith(compareByDescending<SupportMessage> { Instant.parse(it.createdAt) }.thenByDescending { it.id }),
            unread = if(notify && !latestVisible) current.unread + added else current.unread,
            scrollSignal = current.scrollSignal + if(notify && latestVisible && added > 0) 1 else 0)
    }
    fun updateDraft(value: String) {
        val current = mutable.value
        if(current.sending) return
        val changed = value.trim() != current.draft.trim()
        if(changed) sendSnapshot = null
        mutable.value = current.copy(draft = value, invalidDraft = false, sendError = null,
            unknownSend = if(changed) false else current.unknownSend, previousUnknown = current.previousUnknown || changed && current.unknownSend)
    }
    @OptIn(ExperimentalUuidApi::class)
    fun send() {
        val current = mutable.value; val id = current.selectedId ?: return
        if(current.sending || current.statusBusy || current.statusUnknown || !supportCanMessage(current.request?.status) || session.tokens.value == null) return
        if(supportTextLength(current.draft.trim()) !in 1..5000) { mutable.value = current.copy(invalidDraft = true); return }
        val payload = sendSnapshot ?: SupportMessageCreate(current.draft.trim(),Uuid.random().toString()).also { sendSnapshot = it }
        val expected = session.generation; val context = epoch; val sequence = ++sendSequence
        mutable.value = current.copy(sending = true, sendError = null, invalidDraft = false)
        viewModelScope.launch {
            try {
                val result = repository.send(id,payload)
                if(expected != session.generation || context != epoch || sequence != sendSequence) return@launch
                merge(listOf(result))
                sendSnapshot = null
                mutable.value = mutable.value.copy(sending = false, draft = "", unknownSend = false, previousUnknown = false)
                readDetails()
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected != session.generation || context != epoch || sequence != sendSequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(sending = false, sendError = message(error),
                    invalidDraft = api?.statusCode == 422,
                    unknownSend = api == null || api.statusCode >= 500 && api.errorCode != "RefreshUnavailable")
                if(api?.errorCode == "SupportRequestClosedError") readDetails()
            }
        }
    }
    private fun stopPolling() { pollJob?.cancel(); updatesJob?.cancel(); pollJob = null; updatesJob = null; foreground = false }
    fun setForeground(active: Boolean) {
        if(!active) { stopPolling(); mutable.value = mutable.value.copy(newLoading = false); return }
        if(foreground || mutable.value.selectedId == null || session.tokens.value == null) return
        foreground = true
        pollJob = viewModelScope.launch {
            while(isActive) { delay(10_000); if(!mutable.value.statusUnknown) readDetails(); refreshNew(); updatesJob?.join() }
        }
    }
    fun refreshNew() {
        val id = mutable.value.selectedId ?: return
        if(mutable.value.newLoading || session.tokens.value == null) return
        val after = highWater ?: return
        val expected = session.generation; val context = epoch
        mutable.value = mutable.value.copy(newLoading = true,newError = null)
        updatesJob = viewModelScope.launch {
            try {
                var cursor: String? = null
                val cursors = mutableSetOf<String>()
                val collected = mutableListOf<SupportMessage>()
                do {
                    val page = repository.messages(id,cursor,afterId = after)
                    if(expected != session.generation || context != epoch || mutable.value.selectedId != id) return@launch
                    require(page.items.all { it.id > after })
                    collected.addAll(page.items)
                    cursor = page.nextCursor
                    check(cursor == null || cursors.add(cursor))
                } while(cursor != null)
                ensureActive()
                if(expected != session.generation || context != epoch || mutable.value.selectedId != id) return@launch
                merge(collected)
                highWater = maxOf(after,collected.maxOfOrNull { it.id } ?: after)
                mutable.value = mutable.value.copy(newLoading = false)
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected == session.generation && context == epoch && mutable.value.selectedId == id)
                    mutable.value = mutable.value.copy(newLoading = false,newError = message(error))
            }
        }
    }
    fun close() { stopPolling(); sendSequence++; sendSnapshot = null; highWater = null; epoch++; listSequence++; detailSequence++; messageSequence++; listCursors.clear(); messageCursors.clear(); mutable.value = SupportHistoryState() }
    fun back() {
        if (mutable.value.fromCreation || mutable.value.selectedId == null) close()
        else { stopPolling(); sendSequence++; sendSnapshot = null; highWater = null; detailSequence++; messageSequence++; mutable.value = mutable.value.copy(selectedId = null, request = null, statusBusy = false, statusUnknown = false, statusError = null,
            messages = emptyList(), olderCursor = null, detailLoading = false, messagesLoading = false, detailError = null, messagesError = null, draft = "", sending = false, sendError = null, unknownSend = false, previousUnknown = false, newError = null, newLoading = false, unread = 0)
            reload()
        }
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
                val page = current.eventId?.let { repository.eventList(it,cursor) } ?: repository.list(cursor)
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
        epoch++
        listSequence++
        stopPolling(); sendSequence++; sendSnapshot = null; highWater = null; latestVisible = true; detailSequence++; messageSequence++; messageCursors.clear()
        mutable.value = mutable.value.copy(open = true, selectedId = id, fromCreation = fromCreation, loading = false, statusBusy = false, statusUnknown = false, statusError = null,
            request = null, messages = emptyList(), olderCursor = null, detailLoading = false, messagesLoading = false,
            detailError = null, messagesError = null, draft = "", sending = false, invalidDraft = false, sendError = null, unknownSend = false, previousUnknown = false, newError = null, newLoading = false, unread = 0)
        refreshThread()
    }
    fun refreshThread() { readDetails(); if (highWater == null) loadMessages(false) else refreshNew() }
    fun readDetails() {
        val id = mutable.value.selectedId ?: return
        if (mutable.value.detailLoading || mutable.value.statusBusy || session.tokens.value == null) return
        val expected = session.generation; val context = epoch; val sequence = ++detailSequence
        mutable.value = mutable.value.copy(detailLoading = true, detailError = null)
        viewModelScope.launch {
            try {
                val result = repository.get(id)
                mutable.value.eventId?.let { require(result.eventId == it) }
                if (expected == session.generation && context == epoch && sequence == detailSequence)
                    mutable.value = mutable.value.copy(request = result, detailLoading = false, statusUnknown = false, items = mutable.value.items.map { if(it.id == result.id) result else it })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && context == epoch && sequence == detailSequence)
                    mutable.value = mutable.value.copy(detailLoading = false, detailError = message(error))
            }
        }
    }
    fun older() = loadMessages(true)
    fun retryMessages() { if(highWater == null) loadMessages(false) else refreshNew() }
    fun retryMessagePage() { if(messageAppend) older() else retryMessages() }
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
                if (!append) highWater = page.items.maxOfOrNull { it.id } ?: 0
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
fun supportStatusLabel(value: String): String = when(canonicalSupportStatus(value)) {
    "open" -> stringResource(Res.string.support_status_open)
    "in_progress" -> stringResource(Res.string.support_status_progress)
    "waiting_for_customer" -> stringResource(Res.string.support_status_waiting)
    "resolved" -> stringResource(Res.string.support_status_resolved)
    else -> value
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.selectedId,authenticated,lifecycle) {
        if(state.selectedId != null && authenticated) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.setForeground(true)
            try { awaitCancellation() } finally { vm.setForeground(false) }
        }
    }
    DisposableEffect(vm) { onDispose { vm.setForeground(false) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::back) { Text(stringResource(Res.string.support_back)) }
        Text(stringResource(if(state.eventId == null) Res.string.support_history else Res.string.support_event_requests),style = MaterialTheme.typography.headlineMedium)
        if(state.eventId != null) Text(state.eventTitle)
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
                            Text(supportStatusLabel(request.status))
                            if(state.eventId != null) Text(request.authorName ?: request.authorEmail ?: stringResource(Res.string.support_author_visitor))
                            Text(request.updatedAt); SupportContext(request)
                        }
                    }
                }
                if (state.nextCursor != null) item { TextButton(onClick = vm::more, enabled = !state.loading) { Text(stringResource(Res.string.support_more)) } }
            }
        } else key(state.selectedId) {
            var statusDialog by remember { mutableStateOf(false) }
            val scroll = rememberLazyListState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(scroll) {
                snapshotFlow { scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0 }
                    .distinctUntilChanged().collect(vm::visibleLatest)
            }
            LaunchedEffect(state.scrollSignal) { if(state.scrollSignal > 0) scroll.scrollToItem(0) }
            TextButton(onClick = vm::refreshThread,enabled = !state.detailLoading && !state.messagesLoading) { Text(stringResource(Res.string.support_refresh)) }
            if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            SupportReadError(state.detailError,vm::readDetails)
            state.request?.let { request ->
                Text("#${request.id} ${request.subject}",style = MaterialTheme.typography.titleLarge)
                Text(supportStatusLabel(request.status))
                SupportContext(request)
                if(request.canManageStatus) TextButton(onClick = { statusDialog = true },enabled = !state.statusBusy && !state.sending && !state.statusUnknown && !state.detailLoading) {
                    Text(stringResource(Res.string.support_change_status))
                }
                if(statusDialog) AlertDialog(onDismissRequest = { statusDialog = false },
                    title = { Text(stringResource(Res.string.support_change_status)) },
                    text = { Column { supportTransitions(request.status).forEach { target ->
                        TextButton(onClick = { statusDialog = false; vm.changeStatus(target) }) {
                            Text(if(canonicalSupportStatus(request.status) == "resolved" && target == "open") stringResource(Res.string.support_reopen) else supportStatusLabel(target))
                        }
                    } } },confirmButton = {}, dismissButton = { TextButton(onClick = { statusDialog = false }) { Text(stringResource(Res.string.support_back)) } })
            }
            if(state.statusBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.statusError != null) { Text(stringResource(Res.string.support_status_failed),color = MaterialTheme.colorScheme.error); Text(state.statusError!!) }
            if(state.statusUnknown) { Text(stringResource(Res.string.support_status_unknown)); TextButton(onClick = vm::readDetails,enabled = !state.detailLoading) { Text(stringResource(Res.string.support_check_request)) } }
            SupportReadError(state.messagesError,vm::retryMessagePage)
            SupportReadError(state.newError,vm::refreshNew)
            if(state.unread > 0) TextButton(onClick = { scope.launch { scroll.animateScrollToItem(0); vm.visibleLatest(true) } }) {
                Text("${stringResource(Res.string.support_new_messages)} (${state.unread})")
            }
            if (state.messagesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.messagesLoading && state.messagesError == null && state.messages.isEmpty()) Text(stringResource(Res.string.support_messages_empty))
            // Stable IDs + reverse layout keep the visible message anchored when older pages are appended.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state = scroll,reverseLayout = true,verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages,key = { it.id }) { message ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text((message.authorName?.let { "$it · " } ?: "") + stringResource(when(message.authorRole) { "visitor" -> Res.string.support_author_visitor; "organizer" -> Res.string.support_author_organizer; else -> Res.string.support_author_other }),style = MaterialTheme.typography.labelLarge)
                        Text(message.text); Text(message.createdAt,style = MaterialTheme.typography.labelSmall)
                    } }
                }
                if(state.olderCursor != null) item(key = "older") { TextButton(onClick = vm::older,enabled = !state.messagesLoading) { Text(stringResource(Res.string.support_older)) } }
            }
            val writable = supportCanMessage(state.request?.status) && !state.statusBusy && !state.statusUnknown
            if(canonicalSupportStatus(state.request?.status.orEmpty()) == "resolved") Text(stringResource(Res.string.support_resolved_help))
            OutlinedTextField(value = state.draft,onValueChange = vm::updateDraft,modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                enabled = writable && !state.sending,label = { Text(stringResource(Res.string.support_compose_message)) },isError = state.invalidDraft,maxLines = 4)
            if(state.invalidDraft) Text(stringResource(Res.string.support_message_error),color = MaterialTheme.colorScheme.error)
            if(state.sendError != null) { Text(stringResource(Res.string.support_send_error),color = MaterialTheme.colorScheme.error); Text(state.sendError!!) }
            if(state.unknownSend || state.previousUnknown) Text(stringResource(Res.string.support_message_unknown))
            Button(onClick = vm::send,enabled = writable && !state.sending) {
                Text(stringResource(if(state.sending) Res.string.support_sending else Res.string.support_send_message))
            }
        }
    }
}
