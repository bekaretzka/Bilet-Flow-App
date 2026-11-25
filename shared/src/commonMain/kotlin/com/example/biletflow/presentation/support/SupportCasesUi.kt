package com.example.biletflow.presentation.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class SupportCasesState(val open:Boolean=false,val eventId:Int?=null,val eventTitle:String="",val fromCreation:Boolean=false,
    val items:List<SupportCase> = emptyList(),val loading:Boolean=false,val error:String?=null,
    val selectedId:Int?=null,val detail:SupportCase?=null)

/** Array lists and a read-only handoff to backend case IDs. Legacy thread mutations are intentionally not used. */
class SupportCasesViewModel(client:HttpClient,requests:SessionOrdersRepository,private val session:SessionStore,baseUrl:String=apiBaseUrl):ViewModel() {
    private val repository=SupportCasesRepository(client,requests,baseUrl)
    private val mutable=MutableStateFlow(SupportCasesState())
    val state=mutable.asStateFlow()
    private var sequence=0
    private var generation=session.generation
    private var authenticated=session.tokens.value!=null
    private var job:Job?=null
    init {viewModelScope.launch {session.tokens.collect {
        if(generation!=session.generation) {
            val resume=!authenticated && it!=null && mutable.value.open && mutable.value.selectedId==null
            generation=session.generation
            if(resume) reload() else close()
        }
        authenticated=it!=null
    }}}
    fun open() {close();mutable.value=SupportCasesState(open=true);reload()}
    fun openEvent(id:Int,title:String) {close();mutable.value=SupportCasesState(open=true,eventId=id,eventTitle=title);reload()}
    fun close() {sequence++;job?.cancel();mutable.value=SupportCasesState()}
    fun select(id:Int,fromCreation:Boolean=false) {
        sequence++;job?.cancel()
        mutable.value=mutable.value.copy(open=true,selectedId=id,fromCreation=fromCreation,detail=null,loading=false,error=null)
        reload()
    }
    fun back() {
        val current=mutable.value
        if(current.fromCreation || current.selectedId==null) close()
        else {sequence++;job?.cancel();mutable.value=current.copy(selectedId=null,detail=null,loading=false,error=null);reload()}
    }
    fun reload() {
        val current=mutable.value
        if(!current.open || session.tokens.value==null) return
        val expected=session.generation;val request=++sequence
        job?.cancel();mutable.value=current.copy(loading=true,error=null)
        job=viewModelScope.launch {
            try {
                if(current.selectedId!=null) {
                    val item=repository.get(current.selectedId)
                    require(current.eventId==null || item.eventId==current.eventId)
                    if(expected==session.generation && request==sequence) mutable.value=mutable.value.copy(loading=false,detail=item)
                } else {
                    val items=current.eventId?.let {repository.eventInbox(it)} ?: repository.mine()
                    if(expected==session.generation && request==sequence) mutable.value=mutable.value.copy(loading=false,items=items.distinctBy {it.id})
                }
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(expected==session.generation && request==sequence) mutable.value=mutable.value.copy(loading=false,error=
                    (error as? SupportApiException)?.let {"HTTP ${it.statusCode}: ${it.code ?: it.message}"} ?: "network")
            }
        }
    }
}
@Composable
private fun CaseContext(item:SupportCase) {
    item.eventId?.let {Text("${stringResource(Res.string.support_event)} #$it")}
    item.orderId?.let {Text("${stringResource(Res.string.order_result_number)} #$it")}
    item.ticketId?.let {Text("${stringResource(Res.string.attendees_ticket)} #$it")}
}
@Composable
fun SupportCasesPage(vm:SupportCasesViewModel,authenticated:Boolean,onLogin:()->Unit) {
    val state by vm.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick=vm::back) {Text(stringResource(Res.string.support_back))}
            Text(stringResource(if(state.eventId==null) Res.string.support_history else Res.string.support_event_requests),style=MaterialTheme.typography.headlineMedium)
            if(state.eventTitle.isNotEmpty()) Text(state.eventTitle)
        }
        if(!authenticated) item {Text(stringResource(Res.string.support_login_prompt));Button(onClick=onLogin) {Text(stringResource(Res.string.auth_login_button))}}
        else {
            item {TextButton(onClick=vm::reload,enabled=!state.loading) {Text(stringResource(Res.string.support_refresh))}}
            if(state.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
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
                item {Text("#${record.id} ${record.subject}");Text(supportStatusLabel(record.status));CaseContext(record);Text(record.updatedAt)
                    Text(stringResource(Res.string.support_case_thread_pending))}
                items(record.messages.sortedBy {it.id},key={it.id}) {message ->
                    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                        message.authorUserId?.let {Text("${stringResource(Res.string.support_case_author)} #$it")}
                        Text(message.body);Text(message.createdAt)
                    }}
                }
            }
        }
    }
}
