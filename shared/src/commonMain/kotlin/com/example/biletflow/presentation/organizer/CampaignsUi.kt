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



    init { viewModelScope.launch { session.tokens.collect {
        if(generation != session.generation) { generation=session.generation; close() }
    } } }
    fun close() { epoch++; listSequence++; formSequence++; mutable.value=CampaignsState() }
    fun open(id: Int,title: String) { close(); mutable.value=CampaignsState(eventId=id,title=title); reload() }
    fun back() {
        if(mutable.value.saving) return
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
                mutable.value=mutable.value.copy(saving=false,saveError=message(error),fields=api?.fields.orEmpty()+when(api?.code) {"PromoCodeAlreadyExistsError"->setOf("code");"InvalidCampaignError"->setOf("ticket_type_ids");else->emptySet()},denied=denied(error),
                    unknown=api?.let { it.status >= 500 } ?: (error !is OrdersApiException))
            }
        }
    }
}

@Composable
fun CampaignsPage(vm: CampaignsViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick=vm::back,enabled=!state.saving) { Text(stringResource(Res.string.create_back)) }
        Text(stringResource(Res.string.campaigns_title),style=MaterialTheme.typography.headlineMedium)
        Text(state.title)
        if(!state.form) {
            Button(onClick=vm::createForm) { Text(stringResource(Res.string.campaigns_create)) }
            TextButton(onClick=vm::reload,enabled=!state.loading) { Text(stringResource(Res.string.support_refresh)) }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(stringResource(Res.string.campaigns_error)); Text(it); TextButton(onClick=vm::retry) { Text(stringResource(Res.string.events_retry)) } }
            if(!state.loading && state.error == null && state.items.isEmpty()) Text(stringResource(Res.string.campaigns_empty))
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(state.items,key={it.id}) { item -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(item.name,style=MaterialTheme.typography.titleMedium); Text(item.code)
                    Text("${item.discountValue}${if(item.discountType == "percent") "%" else " ₸"}")
                    Text(item.startsAt ?: stringResource(Res.string.campaigns_no_start)); Text(item.endsAt ?: stringResource(Res.string.campaigns_no_end))
                    Text("${stringResource(Res.string.campaigns_limit)}: ${item.maxRedemptions ?: stringResource(Res.string.campaigns_unlimited)}")
                    Text("${stringResource(Res.string.campaigns_redemptions)}: ${item.redemptionsCount}")
                } } }
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.campaigns_dates_help))
            listOf("name" to Res.string.campaigns_name,"code" to Res.string.campaigns_code,
                "discount_value" to Res.string.campaigns_value,"starts_at" to Res.string.campaigns_start,
                "ends_at" to Res.string.campaigns_end,"max_redemptions" to Res.string.campaigns_limit).forEach { (key,label) ->
                OutlinedTextField(state.values[key].orEmpty(),{vm.update(key,it)},label={Text(stringResource(label))},modifier=Modifier.fillMaxWidth(),enabled=!state.saving,isError=key in state.fields,singleLine=true)
                if(key in state.fields) Text(stringResource(when(key) {
                    "name" -> Res.string.campaigns_name_error; "code" -> Res.string.campaigns_code_error
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
            if(state.unknown || state.previousUnknown) {
                Text(stringResource(Res.string.campaigns_unknown)); TextButton(onClick=vm::reload,enabled=!state.loading) {Text(stringResource(Res.string.campaigns_check_list))}
                state.items.forEach {Text("#${it.id} ${it.name} · ${it.code}")}
            }
            Button(onClick=vm::save,enabled=!state.saving && !state.typesLoading && state.typesError == null && !state.unknown && !state.previousUnknown) { Text(stringResource(Res.string.campaigns_save)) }
            if(state.saving) CircularProgressIndicator()
        }
    }
}
