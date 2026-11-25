package com.example.biletflow.presentation.organizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.stringResource



data class CampaignsState(
    val eventId: Int? = null,val title: String = "", val items: List<Campaign> = emptyList(),
    val loading: Boolean = false,val error: String? = null,
    val form: Boolean = false,val values: Map<String,String> = emptyMap(),val selected: Set<Int> = emptySet(),
    val types: List<TicketType> = emptyList(),val typesLoading: Boolean = false,val typesError: String? = null,
    val saving: Boolean = false,val fields: Set<String> = emptySet(),val saveError: String? = null,
    val unknown: Boolean = false,val previousUnknown: Boolean = false,val denied: Boolean = false,
    val reconciling: Boolean = false,val notFound: Boolean = false,
    val detail: Campaign? = null,val detailLoading: Boolean = false,val detailError: String? = null,
    val editing: Boolean = false,val edit: Map<String,String> = emptyMap(),val editSaving: Boolean = false,
    val editFields: Set<String> = emptySet(),val editError: String? = null,val editUnknown: Boolean = false,
)
class CampaignsViewModel(client: HttpClient,requests: SessionOrdersRepository,private val session: SessionStore,
    baseUrl: String = apiBaseUrl): ViewModel() {
    private val repository=CampaignsRepository(client,requests,baseUrl)
    private val tickets=OrganizerTicketTypesRepository(client,requests,baseUrl)
    private val mutable=MutableStateFlow(CampaignsState())
    val state=mutable.asStateFlow()
    private var generation=session.generation
    private var epoch=0
    private var listSequence=0
    private var formSequence=0
    private var detailSequence=0
    private var pending:Pair<CampaignCreate,Set<Int>>?=null



    init { viewModelScope.launch { session.tokens.collect {
        if(generation != session.generation) { generation=session.generation; close() }
    } } }
    fun close() { epoch++; listSequence++; formSequence++; detailSequence++; pending=null; mutable.value=CampaignsState() }
    fun open(id: Int,title: String) { close(); mutable.value=CampaignsState(eventId=id,title=title); reload() }
    fun back() {
        if(mutable.value.saving || mutable.value.editSaving) return
        if(mutable.value.detail!=null || mutable.value.detailLoading) { closeDetail(); return }
        if(!mutable.value.form) close() else {
            formSequence++
            mutable.value=mutable.value.copy(form=false,values=emptyMap(),selected=emptySet(),types=emptyList(),typesLoading=false,typesError=null,
                fields=emptySet(),saveError=null,unknown=false,previousUnknown=false)
        }
    }
    private fun message(error: Exception)=(error as? CampaignApiException)?.let { "HTTP ${it.status}: ${it.code ?: it.message}" }
        ?: (error as? OrdersApiException)?.let { "HTTP ${it.statusCode}: ${it.errorCode ?: it.message}" } ?: "network"
    private fun denied(error: Exception)= (error as? CampaignApiException)?.status == 403 || (error as? OrdersApiException)?.statusCode == 403
    fun retry()=reload()
    fun reload() {
        val current=mutable.value;val id=current.eventId ?: return
        if(current.loading) return
        val expected=session.generation;val context=epoch;val sequence=++listSequence
        mutable.value=current.copy(loading=true,error=null)
        viewModelScope.launch {
            try {
                val items=repository.list(id)
                if(expected==session.generation && context==epoch && sequence==listSequence) mutable.value=mutable.value.copy(loading=false,items=items)
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(expected==session.generation && context==epoch && sequence==listSequence) mutable.value=mutable.value.copy(loading=false,error=message(error),denied=denied(error))
            }
        }
    }
    fun createForm() {
        if(mutable.value.eventId == null || mutable.value.saving) return
        formSequence++
        mutable.value=mutable.value.copy(form=true,values=mapOf("discount_type" to "percent"),selected=emptySet(),fields=emptySet(),saveError=null,unknown=false,previousUnknown=false)
        loadTypes()
    }
    fun loadTypes() {
        val id=mutable.value.eventId ?: return
        if(mutable.value.typesLoading || !mutable.value.form) return
        val expected=session.generation; val context=epoch; val sequence=formSequence
        mutable.value=mutable.value.copy(typesLoading=true,typesError=null)
        viewModelScope.launch {
            try {
                val result=tickets.list(id)
                if(expected == session.generation && context == epoch && sequence == formSequence)
                    mutable.value=mutable.value.copy(typesLoading=false,types=result.filter {it.price>0})
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected == session.generation && context == epoch && sequence == formSequence)
                    mutable.value=mutable.value.copy(typesLoading=false,typesError=message(error),denied=denied(error))
            }
        }
    }
    fun update(key: String,value: String) {
        val current=mutable.value
        if(current.saving) return
        val changed=if(key == "code") current.values[key].orEmpty().trim().uppercase() != value.trim().uppercase() else current.values[key].orEmpty().trim() != value.trim()

        mutable.value=current.copy(values=current.values+(key to value),fields=current.fields-key,saveError=null,
            unknown=if(changed) false else current.unknown,previousUnknown=current.previousUnknown || changed && current.unknown)
    }
    fun toggle(id: Int) {
        val current=mutable.value
        if(current.saving || current.types.none { it.id == id }) return

        mutable.value=current.copy(selected=if(id in current.selected) current.selected-id else current.selected+id,
            fields=current.fields-"ticket_type_ids",saveError=null,unknown=false,previousUnknown=current.previousUnknown || current.unknown)
    }

    fun save() {
        val current=mutable.value; val id=current.eventId ?: return
        if(!current.form || current.saving || current.typesLoading || current.typesError != null || current.unknown || current.previousUnknown) return
        fun value(key: String)=current.values[key].orEmpty().trim()
        val payload=CampaignCreate(value("name"),value("code").uppercase().ifEmpty {null},value("discount_type"),value("discount_value").toLongOrNull() ?: 0,
            value("starts_at").ifEmpty {null},value("ends_at").ifEmpty {null},
            if(value("max_redemptions").isEmpty()) null else value("max_redemptions").toLongOrNull() ?: 0,current.selected.sorted())
        val errors=validateCampaign(payload)
        if(errors.isNotEmpty()) {mutable.value=current.copy(fields=errors);return}
        val expected=session.generation; val context=epoch; val sequence=formSequence
        mutable.value=current.copy(saving=true,fields=emptySet(),saveError=null)
        viewModelScope.launch {
            try {
                repository.create(id,payload)
                if(expected != session.generation || context != epoch || sequence != formSequence) return@launch
                formSequence++
                mutable.value=mutable.value.copy(saving=false,form=false,values=emptyMap(),selected=emptySet(),unknown=false,previousUnknown=false)
                // A response from an older list request must not hide the newly created campaign.
                listSequence++; mutable.value=mutable.value.copy(loading=false); reload()
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected != session.generation || context != epoch || sequence != formSequence) return@launch
                val api=error as? CampaignApiException
                val unknownResult=api?.let { it.status >= 500 } ?: (error !is OrdersApiException)
                if(unknownResult) pending=payload to current.items.map {it.id}.toSet()
                mutable.value=mutable.value.copy(saving=false,saveError=message(error),fields=api?.fields.orEmpty()+when(api?.code) {"PromoCodeAlreadyExistsError"->setOf("code");"InvalidCampaignError"->setOf("ticket_type_ids");else->emptySet()},denied=denied(error),
                    unknown=unknownResult)
                if(unknownResult) reconcile()
            }
        }
    }
    /** Re-reads the list once after an unknown POST outcome; never re-sends the POST. */
    fun reconcile() {
        val current=mutable.value; val id=current.eventId ?: return; val (input,before)=pending ?: return
        if(current.reconciling) return
        val expected=session.generation; val context=epoch; val sequence=formSequence
        mutable.value=current.copy(reconciling=true,notFound=false)
        viewModelScope.launch {
            try {
                val items=repository.list(id)
                if(expected != session.generation || context != epoch || sequence != formSequence) return@launch
                val found=findCreatedCampaign(items,before,input)
                listSequence++
                if(found!=null) { pending=null; formSequence++
                    mutable.value=mutable.value.copy(reconciling=false,items=items,form=false,values=emptyMap(),selected=emptySet(),unknown=false,previousUnknown=false,saveError=null,loading=false,detail=found)
                } else mutable.value=mutable.value.copy(reconciling=false,items=items,notFound=true,loading=false)
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected == session.generation && context == epoch && sequence == formSequence) mutable.value=mutable.value.copy(reconciling=false,notFound=true)
            }
        }
    }
    fun openDetail(campaignId:Int) {
        if(mutable.value.saving) return
        val expected=session.generation; val context=epoch; val sequence=++detailSequence
        mutable.value=mutable.value.copy(detail=mutable.value.items.firstOrNull {it.id==campaignId},detailLoading=true,detailError=null,editing=false,editUnknown=false,editError=null)
        viewModelScope.launch {
            try {
                val item=repository.get(campaignId)
                if(expected==session.generation && context==epoch && sequence==detailSequence) mutable.value=mutable.value.copy(detail=item,detailLoading=false,
                    items=mutable.value.items.map {if(it.id==item.id) item else it})
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected==session.generation && context==epoch && sequence==detailSequence) mutable.value=mutable.value.copy(detailLoading=false,detailError=message(error),denied=denied(error))
            }
        }
    }
    fun closeDetail() { if(mutable.value.editSaving) return; detailSequence++
        mutable.value=mutable.value.copy(detail=null,detailLoading=false,detailError=null,editing=false,edit=emptyMap(),editFields=emptySet(),editError=null,editUnknown=false); reload() }
    fun startEdit() { val item=mutable.value.detail ?: return; if(mutable.value.editSaving) return
        mutable.value=mutable.value.copy(editing=true,editFields=emptySet(),editError=null,edit=mapOf("name" to item.name,"starts_at" to item.startsAt.orEmpty(),
            "ends_at" to item.endsAt.orEmpty(),"max_redemptions" to (item.maxRedemptions?.toString() ?: ""))) }
    fun cancelEdit() { if(!mutable.value.editSaving) mutable.value=mutable.value.copy(editing=false,edit=emptyMap(),editFields=emptySet(),editError=null) }
    fun updateEdit(key:String,value:String) { if(!mutable.value.editSaving) mutable.value=mutable.value.copy(edit=mutable.value.edit+(key to value),editFields=mutable.value.editFields-key,editError=null) }
    fun saveEdit() = patch(mutable.value.edit)
    fun setActive(active:Boolean) = patch(mapOf("is_active" to active.toString()))
    private fun patch(values:Map<String,String>) {
        val current=mutable.value; val item=current.detail ?: return
        if(current.editSaving || current.detailLoading || current.editUnknown) return
        val (body,errors)=campaignPatch(item,values)
        if(errors.isNotEmpty()) { mutable.value=current.copy(editFields=errors); return }
        if(body.isEmpty()) { mutable.value=current.copy(editing=false,edit=emptyMap()); return }
        val expected=session.generation; val context=epoch; val sequence=++detailSequence
        mutable.value=current.copy(editSaving=true,editError=null,editFields=emptySet())
        viewModelScope.launch {
            try {
                val updated=repository.update(item.id,body)
                if(expected==session.generation && context==epoch && sequence==detailSequence) mutable.value=mutable.value.copy(editSaving=false,detail=updated,editing=false,edit=emptyMap(),
                    items=mutable.value.items.map {if(it.id==updated.id) updated else it})
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected!=session.generation || context!=epoch || sequence!=detailSequence) return@launch
                val api=error as? CampaignApiException
                mutable.value=mutable.value.copy(editSaving=false,editError=message(error),denied=denied(error),
                    editFields=api?.fields.orEmpty()+if(api?.code=="InvalidCampaignError") setOf("max_redemptions") else emptySet(),
                    editUnknown=api?.let {it.status>=500} ?: (error !is OrdersApiException))
            }
        }
    }
    /** After an unknown PATCH outcome the documented GET shows the actual state; nothing is re-sent. */
    fun checkEdit() { val id=mutable.value.detail?.id ?: return; if(mutable.value.editSaving) return
        mutable.value=mutable.value.copy(editUnknown=false,editing=false,edit=emptyMap()); openDetail(id) }
}

@Composable
fun CampaignsPage(vm: CampaignsViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick=vm::back,enabled=!state.saving) { Text(stringResource(Res.string.create_back)) }
        Text(stringResource(Res.string.campaigns_title),style=MaterialTheme.typography.headlineMedium)
        Text(state.title)
        if(state.detail!=null || state.detailLoading || state.detailError!=null) CampaignDetail(vm,state,Modifier.weight(1f))
        else if(!state.form) {
            Button(onClick=vm::createForm) { Text(stringResource(Res.string.campaigns_create)) }
            TextButton(onClick=vm::reload,enabled=!state.loading) { Text(stringResource(Res.string.support_refresh)) }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(stringResource(Res.string.campaigns_error)); Text(it); TextButton(onClick=vm::retry) { Text(stringResource(Res.string.events_retry)) } }
            if(!state.loading && state.error == null && state.items.isEmpty()) Text(stringResource(Res.string.campaigns_empty))
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(state.items,key={it.id}) { item -> Card(onClick={vm.openDetail(item.id)},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(item.name,style=MaterialTheme.typography.titleMedium); Text(item.code)
                    Text("${item.discountValue}${if(item.discountType == "percent") "%" else " ₸"}")
                    Text(item.startsAt ?: stringResource(Res.string.campaigns_no_start)); Text(item.endsAt ?: stringResource(Res.string.campaigns_no_end))
                    Text("${stringResource(Res.string.campaigns_limit)}: ${item.maxRedemptions ?: stringResource(Res.string.campaigns_unlimited)}")
                    Text("${stringResource(Res.string.campaigns_redemptions)}: ${item.redemptionsCount}")
                    Text(stringResource(if(item.isActive) Res.string.campaigns_active else Res.string.campaigns_inactive))
                } } }
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.campaigns_dates_help))
            listOf("name" to Res.string.campaigns_name,"code" to Res.string.campaigns_code,
                "discount_value" to Res.string.campaigns_value,"starts_at" to Res.string.campaigns_start,
                "ends_at" to Res.string.campaigns_end,"max_redemptions" to Res.string.campaigns_limit).forEach { (key,label) ->
                OutlinedTextField(state.values[key].orEmpty(),{vm.update(key,it)},label={Text(stringResource(label))},modifier=Modifier.fillMaxWidth(),enabled=!state.saving,isError=key in state.fields,singleLine=true)
                if(key in state.fields) Text(stringResource(when(key) {
                    "name" -> Res.string.campaigns_name_error
                    "code" -> if(state.saveError?.contains("PromoCodeAlreadyExistsError") == true) Res.string.campaigns_code_exists else Res.string.campaigns_code_error
                    "discount_value" -> Res.string.campaigns_value_error; "max_redemptions" -> Res.string.campaigns_limit_error
                    else -> Res.string.campaigns_date_error
                }),color=MaterialTheme.colorScheme.error)
            }
            Row { listOf("percent" to Res.string.campaigns_percent,"fixed" to Res.string.campaigns_fixed).forEach { (value,label) ->
                FilterChip(selected=state.values["discount_type"]==value,onClick={vm.update("discount_type",value)},enabled=!state.saving,label={Text(stringResource(label))})
            } }
            Text(stringResource(Res.string.campaigns_types_help))
            if(state.typesLoading) CircularProgressIndicator()
            state.typesError?.let { Text(it); TextButton(onClick=vm::loadTypes) { Text(stringResource(Res.string.events_retry)) } }
            state.types.forEach { type ->
                FilterChip(selected=type.id in state.selected,onClick={vm.toggle(type.id)},enabled=!state.saving,
                    label={ Text("#${type.id} ${type.name}" + if(type.isHidden) " · ${stringResource(Res.string.ticket_hidden)}" else "") })
            }
            if("ticket_type_ids" in state.fields) Text(stringResource(Res.string.campaigns_types_error),color=MaterialTheme.colorScheme.error)
            state.saveError?.let { Text(stringResource(Res.string.campaigns_error)); Text(it) }
            if(state.reconciling) { Text(stringResource(Res.string.campaigns_reconciling)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(state.notFound) Text(stringResource(Res.string.campaigns_result_unknown),color=MaterialTheme.colorScheme.error)
            if(state.unknown || state.previousUnknown) {
                Text(stringResource(Res.string.campaigns_unknown)); TextButton(onClick=vm::reconcile,enabled=!state.reconciling) {Text(stringResource(Res.string.campaigns_check_list))}
                state.items.forEach {Text("#${it.id} ${it.name} · ${it.code}")}
            }
            Button(onClick=vm::save,enabled=!state.saving && !state.typesLoading && state.typesError == null && !state.unknown && !state.previousUnknown) { Text(stringResource(Res.string.campaigns_save)) }
            if(state.saving) CircularProgressIndicator()
        }
    }
}

@Composable
private fun CampaignDetail(vm: CampaignsViewModel,state: CampaignsState,modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.detailError?.let { Text(stringResource(Res.string.campaigns_error)); Text(it)
            state.detail?.let { item -> TextButton(onClick={vm.openDetail(item.id)}) { Text(stringResource(Res.string.events_retry)) } } }
        val item=state.detail ?: return@Column
        Text(item.name,style=MaterialTheme.typography.titleLarge)
        Text("${stringResource(Res.string.campaigns_code_label)}: ${item.code}")
        Text("${stringResource(Res.string.campaigns_discount)}: ${item.discountValue}${if(item.discountType == "percent") "%" else " ₸"}")
        Text(item.startsAt ?: stringResource(Res.string.campaigns_no_start)); Text(item.endsAt ?: stringResource(Res.string.campaigns_no_end))
        Text("${stringResource(Res.string.campaigns_limit)}: ${item.maxRedemptions ?: stringResource(Res.string.campaigns_unlimited)}")
        Text("${stringResource(Res.string.campaigns_redemptions)}: ${item.redemptionsCount}")
        Text(stringResource(if(item.isActive) Res.string.campaigns_active else Res.string.campaigns_inactive))
        Text("${stringResource(Res.string.campaigns_link_opens)}: ${item.linkOpens}")
        Text(stringResource(Res.string.campaigns_stats),style=MaterialTheme.typography.titleMedium)
        Text("${stringResource(Res.string.campaigns_stat_orders)}: ${item.stats.orders} · ${stringResource(Res.string.campaigns_stat_tickets)}: ${item.stats.tickets}")
        Text("${stringResource(Res.string.campaigns_stat_gross)}: ${item.stats.gross} ₸ · ${stringResource(Res.string.campaigns_stat_discount)}: ${item.stats.discount} ₸ · ${stringResource(Res.string.campaigns_stat_net)}: ${item.stats.net} ₸")
        Text(stringResource(Res.string.campaigns_fixed_help))
        state.editError?.let { Text(stringResource(Res.string.campaigns_error),color=MaterialTheme.colorScheme.error); Text(it) }
        if(state.editUnknown) { Text(stringResource(Res.string.campaigns_edit_unknown)); TextButton(onClick=vm::checkEdit) { Text(stringResource(Res.string.campaigns_check_state)) } }
        if(!state.editing) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=vm::startEdit,enabled=!state.editSaving && !state.editUnknown) { Text(stringResource(Res.string.campaigns_edit)) }
                OutlinedButton(onClick={vm.setActive(!item.isActive)},enabled=!state.editSaving && !state.editUnknown && !state.detailLoading) {
                    Text(stringResource(if(item.isActive) Res.string.campaigns_deactivate else Res.string.campaigns_activate)) }
            }
        } else {
            listOf("name" to Res.string.campaigns_name,"starts_at" to Res.string.campaigns_start,"ends_at" to Res.string.campaigns_end,"max_redemptions" to Res.string.campaigns_limit).forEach { (key,label) ->
                OutlinedTextField(state.edit[key].orEmpty(),{vm.updateEdit(key,it)},label={Text(stringResource(label))},modifier=Modifier.fillMaxWidth(),enabled=!state.editSaving,isError=key in state.editFields,singleLine=true)
                if(key in state.editFields) Text(stringResource(when(key) {"name" -> Res.string.campaigns_name_error; "max_redemptions" -> Res.string.campaigns_limit_below
                    else -> Res.string.campaigns_date_error}),color=MaterialTheme.colorScheme.error)
            }
            Text(stringResource(Res.string.campaigns_clear_help))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=vm::saveEdit,enabled=!state.editSaving && !state.editUnknown) { Text(stringResource(Res.string.campaigns_save)) }
                TextButton(onClick=vm::cancelEdit,enabled=!state.editSaving) { Text(stringResource(Res.string.campaigns_cancel_edit)) }
            }
        }
        if(state.editSaving) CircularProgressIndicator()
    }
}
