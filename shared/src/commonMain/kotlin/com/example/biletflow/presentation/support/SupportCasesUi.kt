package com.example.biletflow.presentation.support

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import com.example.biletflow.presentation.events.EventContextCaption
import com.example.biletflow.domain.repositories.EventsRepository
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

/** Who wrote a message, derived only from author_user_id, the case's requester_user_id and my /auth/me id. */
enum class SupportAuthor { Me, Requester, Support, Deleted }
fun supportAuthor(authorUserId:Int?,requesterUserId:Int,myId:Int?):SupportAuthor = when {
    // backend models/support_case.py: author_user_id is SET NULL when the author's account is deleted
    authorUserId==null -> SupportAuthor.Deleted
    myId!=null && authorUserId==myId -> SupportAuthor.Me
    authorUserId==requesterUserId -> SupportAuthor.Requester
    else -> SupportAuthor.Support
}
/** A failed server answer: [code] is the backend "error" (or older detail), [status] the HTTP status. */
data class SupportFailure(val status:Int?,val code:String?,val message:String)

data class SupportCasesState(val open:Boolean=false,val eventId:Int?=null,val eventTitle:String="",val fromCreation:Boolean=false,
    val items:List<SupportCase> = emptyList(),val loading:Boolean=false,val error:String?=null,
    val selectedId:Int?=null,val detail:SupportCase?=null,val myId:Int?=null,
    val draft:String="",val invalidDraft:Boolean=false,val sending:Boolean=false,val sendError:SupportFailure?=null,val sendUnknown:Boolean=false,
    val statusBusy:Boolean=false,val statusError:SupportFailure?=null,val statusUnknown:Boolean=false,val statusDenied:Boolean=false,
    val unavailable:Boolean=false, val handler:Boolean=false,
    val adminInbox:Boolean=false,val kindFilter:String?=null,val statusFilter:String?=null,
    val assignBusy:Boolean=false,val assignError:SupportFailure?=null,val assignUnknown:Boolean=false,
    val adminQuery:String="",val adminsBusy:Boolean=false,
    val admins:List<AdminCandidate>?=null,val adminsError:String?=null,val isAdmin:Boolean=false) {
    /** Being the requester does not revoke independently verified handler rights. */
    val canManageStatus:Boolean get() = handler && !statusDenied && myId!=null && detail!=null
}

class SupportCasesViewModel(client:HttpClient,requests:SessionOrdersRepository,private val session:SessionStore,baseUrl:String=apiBaseUrl):ViewModel() {
    private val repository=SupportCasesRepository(client,requests,baseUrl)
    private val mutable=MutableStateFlow(SupportCasesState())
    val state=mutable.asStateFlow()
    private var sequence=0
    private var generation=session.generation
    private var authenticated=session.tokens.value!=null
    private var job:Job?=null
    /** Stamp order of every request that may replace the opened case; an older answer never overwrites a newer one. */
    private var caseStamp=0L
    private var appliedStamp=0L
    private val drafts=mutableMapOf<Int,String>()
    init {viewModelScope.launch {session.tokens.collect {
        if(generation!=session.generation) {
            val resume=!authenticated && it!=null && mutable.value.open && mutable.value.selectedId==null
            generation=session.generation
            drafts.clear()
            if(resume) reload() else close()
        }
        authenticated=it!=null
    }}}
    fun open() {close();mutable.value=SupportCasesState(open=true);reload()}
    fun openEvent(id:Int,title:String) {close();mutable.value=SupportCasesState(open=true,eventId=id,eventTitle=title);reload()}
    /** Platform admin inbox: GET /support/cases/inbox (admins see every case) with optional kind and status. */
    fun openAdminInbox() {close();mutable.value=SupportCasesState(open=true,adminInbox=true);reload()}
    fun setInboxFilter(kind:String?,status:String?) {mutable.value=mutable.value.copy(kindFilter=kind,statusFilter=status);reload()}
    fun close() {sequence++;caseStamp++;appliedStamp=caseStamp;job?.cancel();mutable.value=SupportCasesState()}
    fun select(id:Int,fromCreation:Boolean=false) {
        sequence++;job?.cancel();caseStamp++;appliedStamp=caseStamp
        mutable.value=mutable.value.copy(open=true,selectedId=id,fromCreation=fromCreation,detail=null,loading=false,error=null,
            draft=drafts[id].orEmpty(),invalidDraft=false,sending=false,sendError=null,sendUnknown=false,
            statusBusy=false,statusError=null,statusUnknown=false,statusDenied=false,unavailable=false,handler=false,
            assignBusy=false,assignError=null,assignUnknown=false)
        reload()
    }
    fun back() {
        val current=mutable.value
        if(current.fromCreation || current.selectedId==null) close()
        else {sequence++;caseStamp++;appliedStamp=caseStamp;job?.cancel();mutable.value=current.copy(selectedId=null,detail=null,loading=false,error=null);reload()}
    }
    /** Called when the app returns to the foreground while this screen is shown. */
    fun foreground() { if(mutable.value.open) reload() }
    fun reload() {
        val current=mutable.value
        if(!current.open || session.tokens.value==null) return
        val caseId=current.selectedId
        if(caseId!=null) {refreshCase(caseId);return}
        val expected=session.generation;val request=++sequence
        job?.cancel();mutable.value=current.copy(loading=true,error=null)
        job=viewModelScope.launch {
            try {
                val items=if(current.adminInbox) repository.inbox(current.kindFilter,current.statusFilter)
                    else current.eventId?.let {repository.eventInbox(it)} ?: repository.mine()
                if(expected==session.generation && request==sequence) mutable.value=mutable.value.copy(loading=false,items=items.distinctBy {it.id})
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(expected==session.generation && request==sequence) mutable.value=mutable.value.copy(loading=false,error=describe(error))
            }
        }
    }
    private fun live(expected:Long,caseId:Int)=expected==session.generation && mutable.value.selectedId==caseId
    /** Applies a case answer only for the same account and case, and only if no newer answer was applied. */
    private fun applyCase(expected:Long,caseId:Int,stamp:Long,item:SupportCase):Boolean {
        if(!live(expected,caseId) || stamp<=appliedStamp) return false
        val eventId=mutable.value.eventId
        if(item.id!=caseId || eventId!=null && item.eventId!=eventId) return false
        appliedStamp=stamp
        mutable.value=mutable.value.copy(detail=item.copy(messages=item.messages.distinctBy {it.id}.sortedBy {it.id}),unavailable=false)
        return true
    }
    private suspend fun myId(expected:Long):Int? {
        session.user.value?.id?.let {return it}
        val user=repository.me()
        session.setUser(user,expected)
        return user.id
    }
    /** GET case; returns the applied case or null when it failed or was stale. */
    private suspend fun readCase(expected:Long,caseId:Int):SupportCase? {
        val stamp=++caseStamp
        val me=myId(expected)
        val item=repository.get(caseId)
        val user=session.user.value
        // An admin handles every case except the ones they opened themselves; an organizer handles attendee cases of own events.
        val handler=user?.isPlatformAdmin==true && item.requesterUserId!=me || item.kind=="attendee" && item.eventId!=null && user?.organizer?.status=="approved" && repository.ownsEvent(item.eventId,user.organizer.id)
        if(!live(expected,caseId)) return null
        if(me!=null) mutable.value=mutable.value.copy(myId=me,handler=handler,isAdmin=user?.isPlatformAdmin==true)
        return item.takeIf {applyCase(expected,caseId,stamp,it)}
    }
    private fun refreshCase(caseId:Int) {
        val expected=session.generation
        mutable.value=mutable.value.copy(loading=true,error=null)
        viewModelScope.launch {
            try {
                readCase(expected,caseId)
                if(live(expected,caseId)) mutable.value=mutable.value.copy(loading=false)
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(!live(expected,caseId)) return@launch
                val notFound=(error as? SupportApiException)?.let {it.statusCode==404 && it.code=="SupportCaseNotFoundError"}==true
                mutable.value=mutable.value.copy(loading=false,error=describe(error),unavailable=notFound || mutable.value.unavailable)
            }
        }
    }
    fun updateDraft(value:String) {
        val current=mutable.value;val id=current.selectedId ?: return
        if(current.sending) return
        drafts[id]=value
        mutable.value=current.copy(draft=value,invalidDraft=false,sendError=null)
    }
    fun send() {
        val current=mutable.value;val caseId=current.selectedId ?: return;val detail=current.detail ?: return
        if(current.sending) return
        // backend schemas/support.py Text: strip_whitespace, then 1..5000 characters (code points)
        val body=supportTrim(current.draft)
        if(supportTextLength(body) !in 1..5000) {mutable.value=current.copy(invalidDraft=true);return}
        val expected=session.generation
        val known=detail.messages.map {it.id}.toSet()
        mutable.value=current.copy(sending=true,invalidDraft=false,sendError=null,sendUnknown=false)
        viewModelScope.launch {
            try {
                val message=repository.send(caseId,body)
                if(!live(expected,caseId)) return@launch
                val shown=mutable.value.detail
                if(shown!=null) mutable.value=mutable.value.copy(detail=shown.copy(messages=(shown.messages+message).distinctBy {it.id}.sortedBy {it.id}))
                delivered(caseId)
                try {readCase(expected,caseId)} catch(error:Exception) {
                    if(error is CancellationException) throw error
                    if(live(expected,caseId)) mutable.value=mutable.value.copy(error=describe(error))
                }
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(!live(expected,caseId)) return@launch
                val failure=failure(error)
                if(failure!=null) {
                    mutable.value=mutable.value.copy(sending=false,sendError=failure,
                        unavailable=failure.status==404 && failure.code=="SupportCaseNotFoundError")
                    return@launch
                }
                // Result unknown: never POST again; read the case and look for a new message of mine with this body.
                try {
                    val item=readCase(expected,caseId) ?: mutable.value.detail
                    if(!live(expected,caseId)) return@launch
                    val me=mutable.value.myId
                    val found=item?.messages?.any {it.id !in known && me!=null && it.authorUserId==me && it.body==body}==true
                    if(found) delivered(caseId) else mutable.value=mutable.value.copy(sending=false,sendUnknown=true)
                } catch(readError:Exception) {
                    if(readError is CancellationException) throw readError
                    if(live(expected,caseId)) mutable.value=mutable.value.copy(sending=false,sendUnknown=true,error=describe(readError))
                }
            }
        }
    }
    private fun delivered(caseId:Int) {
        drafts.remove(caseId)
        mutable.value=mutable.value.copy(sending=false,draft="",sendError=null,sendUnknown=false)
    }
    fun changeStatus(target:String) {
        val current=mutable.value;val caseId=current.selectedId ?: return;val detail=current.detail ?: return
        if(current.statusBusy || !current.canManageStatus || target !in supportStatuses || target==detail.status) return
        val expected=session.generation;val stamp=++caseStamp
        mutable.value=current.copy(statusBusy=true,statusError=null,statusUnknown=false)
        viewModelScope.launch {
            try {
                val result=repository.changeStatus(caseId,target)
                if(!live(expected,caseId)) return@launch
                if(result.includesMessages) applyCase(expected,caseId,stamp,result.case)
                mutable.value=mutable.value.copy(statusBusy=false)
                if(!result.includesMessages) refreshCase(caseId)
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(!live(expected,caseId)) return@launch
                val failure=failure(error)
                when {
                    failure?.status==403 && failure.code=="SupportCaseAccessDeniedError" -> mutable.value=mutable.value.copy(statusBusy=false,statusDenied=true)
                    failure!=null -> mutable.value=mutable.value.copy(statusBusy=false,statusError=failure,
                        unavailable=failure.status==404 && failure.code=="SupportCaseNotFoundError")
                    // Result unknown: no retry, show the status the server reports now.
                    else -> mutable.value=mutable.value.copy(statusBusy=false,statusUnknown=true)
                }
                if(failure?.status!=404) refreshCase(caseId)
            }
        }
    }
    /** PATCH /assignee. Unknown result: no retry, the case is re-read (GET case) and shows the server's assignee. */
    fun assign(userId:Int?) {
        val current=mutable.value;val caseId=current.selectedId ?: return;val detail=current.detail ?: return
        if(current.assignBusy || !current.canManageStatus || detail.assignedUserId==userId) return
        val expected=session.generation;val stamp=++caseStamp
        mutable.value=current.copy(assignBusy=true,assignError=null,assignUnknown=false)
        viewModelScope.launch {
            try {
                val result=repository.assign(caseId,userId)
                if(!live(expected,caseId)) return@launch
                if(result.includesMessages) applyCase(expected,caseId,stamp,result.case)
                mutable.value=mutable.value.copy(assignBusy=false)
                if(!result.includesMessages) refreshCase(caseId)
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(!live(expected,caseId)) return@launch
                val failure=failure(error)
                mutable.value=if(failure!=null) mutable.value.copy(assignBusy=false,assignError=failure,
                    statusDenied=failure.status==403 && failure.code=="SupportCaseAccessDeniedError" || mutable.value.statusDenied,
                    unavailable=failure.status==404 && failure.code=="SupportCaseNotFoundError")
                    else mutable.value.copy(assignBusy=false,assignUnknown=true)
                if(failure?.status!=404) refreshCase(caseId)
            }
        }
    }
    fun updateAdminQuery(value:String) { mutable.value=mutable.value.copy(adminQuery=value.take(254)) }
    /** Other admins for the picker (admin only): GET /admin/users?q=<email> rows with is_platform_admin=true. */
    fun searchAdmins() {
        val query=mutable.value.adminQuery.trim()
        if(!mutable.value.isAdmin || mutable.value.adminsBusy || query.isEmpty()) return
        val expected=session.generation
        mutable.value=mutable.value.copy(adminsBusy=true,adminsError=null)
        viewModelScope.launch {
            try {
                val found=repository.searchAdmins(query)
                if(expected==session.generation) mutable.value=mutable.value.copy(admins=found,adminsError=null,adminsBusy=false)
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(expected==session.generation) mutable.value=mutable.value.copy(adminsError=describe(error),adminsBusy=false)
            }
        }
    }
    /** A definite server answer, or null when the outcome is unknown (transport failure or 5xx). */
    private fun failure(error:Exception):SupportFailure? = when(error) {
        is SupportApiException -> if(error.statusCode>=500) null else SupportFailure(error.statusCode,error.code,error.message)
        is OrdersApiException -> SupportFailure(error.statusCode,error.errorCode,error.message)
        else -> null
    }
    private fun describe(error:Exception):String = when(error) {
        is SupportApiException -> "HTTP ${error.statusCode}: ${error.code ?: error.message}"
        is OrdersApiException -> "HTTP ${error.statusCode}: ${error.errorCode ?: error.message}"
        else -> "network"
    }
}

@Composable
fun supportStatusLabel(value:String):String = when(value) {
    "open" -> stringResource(Res.string.support_status_open)
    "in_progress" -> stringResource(Res.string.support_status_progress)
    "waiting_for_customer" -> stringResource(Res.string.support_status_waiting)
    "resolved" -> stringResource(Res.string.support_status_resolved)
    else -> value
}
@Composable
private fun supportFailureText(failure:SupportFailure):String = when {
    failure.status==404 && failure.code=="SupportCaseNotFoundError" -> stringResource(Res.string.support_case_unavailable)
    failure.status==403 && failure.code=="SupportCaseAccessDeniedError" -> stringResource(Res.string.support_case_handlers_only)
    failure.status==422 || failure.code=="VALIDATION_ERROR" -> stringResource(Res.string.support_case_invalid)
    else -> stringResource(Res.string.support_case_failed)
}
@Composable
private fun CaseContext(item:SupportCase) {
    item.eventId?.let {Text("${stringResource(Res.string.support_event)} #$it")}
    item.orderId?.let {Text("${stringResource(Res.string.order_result_number)} #$it")}
    item.ticketId?.let {Text("${stringResource(Res.string.attendees_ticket)} #$it")}
}
@Composable
fun SupportCasesPage(vm:SupportCasesViewModel,authenticated:Boolean,eventsRepository:EventsRepository?=null,onLogin:()->Unit) {
    val state by vm.state.collectAsState()
    val listState=rememberLazyListState()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle,vm) {
        var first=true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {if(first) first=false else vm.foreground()}
    }
    val messages=state.detail?.messages.orEmpty()
    // Follow new messages only while the reader is at the bottom; the first load of a case jumps to the last message.
    var stick by remember(state.selectedId) {mutableStateOf(true)}
    var shownCount by remember(state.selectedId) {mutableStateOf(-1)}
    LaunchedEffect(listState,state.selectedId) {
        var total=-1
        snapshotFlow {listState.layoutInfo.totalItemsCount to (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)}.collect {(count,last) ->
            if(count==total) stick=last>=count-1
            total=count
        }
    }
    LaunchedEffect(state.selectedId,messages.size) {
        if(state.selectedId==null || messages.isEmpty()) return@LaunchedEffect
        val firstLoad=shownCount<0
        if(firstLoad || messages.size>shownCount && stick) listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1)-1)
        shownCount=messages.size
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp),state=listState,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick=vm::back) {Text(stringResource(Res.string.support_back))}
            Text(stringResource(when {state.adminInbox -> Res.string.support_admin_inbox; state.eventId==null -> Res.string.support_history; else -> Res.string.support_event_requests}),style=MaterialTheme.typography.headlineMedium)
            if(state.adminInbox && state.selectedId==null) {
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected=state.kindFilter==null,onClick={vm.setInboxFilter(null,state.statusFilter)},label={Text(stringResource(Res.string.admin_any))})
                    listOf("attendee","platform").forEach {kind -> FilterChip(selected=state.kindFilter==kind,onClick={vm.setInboxFilter(kind,state.statusFilter)},label={Text(kind)})}
                }
                Column {
                    FilterChip(selected=state.statusFilter==null,onClick={vm.setInboxFilter(state.kindFilter,null)},label={Text(stringResource(Res.string.admin_any))})
                    supportStatuses.forEach {status -> FilterChip(selected=state.statusFilter==status,onClick={vm.setInboxFilter(state.kindFilter,status)},label={Text(supportStatusLabel(status))})}
                }
            }
            if(state.eventTitle.isNotEmpty()) Text(state.eventTitle)
        }
        if(!authenticated) item {Text(stringResource(Res.string.support_login_prompt));Button(onClick=onLogin) {Text(stringResource(Res.string.auth_login_button))}}
        else {
            item {TextButton(onClick=vm::reload,enabled=!state.loading) {Text(stringResource(Res.string.support_refresh))}}
            if(state.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(state.unavailable) item {Text(stringResource(Res.string.support_case_unavailable),color=MaterialTheme.colorScheme.error)}
            state.error?.let {error ->item {Text(stringResource(Res.string.support_read_error));Text(error);Button(onClick=vm::reload) {Text(stringResource(Res.string.events_retry))}}}
            if(state.selectedId==null) {
                if(!state.loading && state.error==null && state.items.isEmpty()) item {Text(stringResource(Res.string.support_empty))}
                items(state.items,key={it.id}) {item ->Card(onClick={vm.select(item.id)},modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("#${item.id} ${item.subject}");Text(supportStatusLabel(item.status));Text(item.updatedAt)
                        Text("${stringResource(Res.string.support_case_requester)} #${item.requesterUserId}");CaseContext(item)
                    }
                }}
            } else state.detail?.let {record ->
                item {
                    Text("#${record.id} ${record.subject}",style=MaterialTheme.typography.titleLarge)
                    Text("${stringResource(Res.string.support_case_status)}: ${supportStatusLabel(record.status)}")
                    CaseContext(record);record.eventId?.let {EventContextCaption(it,eventsRepository)};Text(record.updatedAt)
                    if(state.canManageStatus) {
                        Text(stringResource(Res.string.support_change_status))
                        Column {
                            supportStatuses.forEach {status ->
                                FilterChip(selected=record.status==status,enabled=!state.statusBusy,onClick={vm.changeStatus(status)},label={Text(supportStatusLabel(status))})
                            }
                        }
                        if(state.statusBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.statusError?.let {Text(stringResource(Res.string.support_status_failed)+" "+supportFailureText(it),color=MaterialTheme.colorScheme.error)}
                    if(state.statusUnknown) Text(stringResource(Res.string.support_status_unknown),color=MaterialTheme.colorScheme.error)
                    // assigned_user_id only: "you" for my id, otherwise the bare user id (no names are invented)
                    Text("${stringResource(Res.string.support_assignee)}: "+when(val assigned=record.assignedUserId) {
                        null -> stringResource(Res.string.support_assignee_none)
                        state.myId -> stringResource(Res.string.support_assignee_me)
                        else -> "${stringResource(Res.string.support_assignee_user)} #$assigned"
                    })
                    if(state.canManageStatus) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={vm.assign(state.myId)},enabled=!state.assignBusy && record.assignedUserId!=state.myId) {Text(stringResource(Res.string.support_assign_me))}
                            OutlinedButton(onClick={vm.assign(null)},enabled=!state.assignBusy && record.assignedUserId!=null) {Text(stringResource(Res.string.support_unassign))}
                        }
                        if(state.isAdmin) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(state.adminQuery,vm::updateAdminQuery,Modifier.weight(1f),singleLine=true,
                                    label={Text(stringResource(Res.string.support_admin_email))})
                                TextButton(onClick=vm::searchAdmins,enabled=!state.adminsBusy && state.adminQuery.isNotBlank()) {Text(stringResource(Res.string.support_assign_other))}
                            }
                            if(state.adminsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            val admins=state.admins?.filter {it.id!=state.myId}
                            if(admins!=null && admins.isEmpty()) Text(stringResource(Res.string.support_admin_none))
                            admins?.forEach {admin ->
                                TextButton(onClick={vm.assign(admin.id)},enabled=!state.assignBusy && record.assignedUserId!=admin.id) {
                                    Text("${stringResource(Res.string.support_assign_to)} ${admin.email ?: "#${admin.id}"}")}
                            }
                            state.adminsError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                        }
                        if(state.assignBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.assignError?.let {Text(stringResource(Res.string.support_assign_failed)+" "+
                        if(it.code=="InvalidSupportAssigneeError") stringResource(Res.string.support_assignee_invalid) else supportFailureText(it),color=MaterialTheme.colorScheme.error)}
                    if(state.assignUnknown) Text(stringResource(Res.string.support_assign_unknown),color=MaterialTheme.colorScheme.error)
                }
                if(record.messages.isEmpty()) item {Text(stringResource(Res.string.support_messages_empty))}
                items(record.messages,key={it.id}) {message ->
                    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                        Text(stringResource(when(supportAuthor(message.authorUserId,record.requesterUserId,state.myId)) {
                            SupportAuthor.Me -> Res.string.support_author_me
                            SupportAuthor.Requester -> Res.string.support_author_requester
                            SupportAuthor.Support -> Res.string.support_author_support
                            SupportAuthor.Deleted -> Res.string.support_author_deleted
                        }),style=MaterialTheme.typography.labelLarge)
                        Text(message.body);Text(message.createdAt,style=MaterialTheme.typography.bodySmall)
                    }}
                }
            }
        }
    }
    if(authenticated && state.selectedId!=null && state.detail!=null) Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp)) {
        if(state.sendUnknown) Text(stringResource(Res.string.support_message_result_unknown),color=MaterialTheme.colorScheme.error)
        state.sendError?.let {Text(stringResource(Res.string.support_send_error)+" "+supportFailureText(it),color=MaterialTheme.colorScheme.error)}
        if(state.invalidDraft) Text(stringResource(Res.string.support_message_error),color=MaterialTheme.colorScheme.error)
        OutlinedTextField(value=state.draft,onValueChange=vm::updateDraft,enabled=!state.sending,isError=state.invalidDraft,
            label={Text(stringResource(Res.string.support_compose_message))},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)
        Button(onClick=vm::send,enabled=!state.sending,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(if(state.sending) Res.string.support_sending else Res.string.support_send_message))
        }
    }
    }
}
