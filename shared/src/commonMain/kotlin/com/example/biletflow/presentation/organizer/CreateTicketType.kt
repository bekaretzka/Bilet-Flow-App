package com.example.biletflow.presentation.organizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import biletflow.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.data.repositories.*
import com.example.biletflow.core.network.apiBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

data class TicketTypeFormState(val eventId: Int? = null, val open: Boolean = false,
    val values: Map<String,String> = emptyMap(), val errors: Set<String> = emptySet(),
    val saving: Boolean = false, val failed: Boolean = false, val unknown: Boolean = false,
    val added: Map<Int,List<TicketType>> = emptyMap(), val editing: TicketType? = null,
    val original: Map<String,String> = emptyMap(), val loaded: Map<Int,List<TicketType>> = emptyMap(),
    val listLoading: Boolean = false, val listError: Boolean = false, val belowSold: Boolean = false,
    val hiding: Set<Int> = emptySet(), val hideUnknown: Set<Int> = emptySet(), val hideErrors: Set<Int> = emptySet())

fun validateTicketType(values: Map<String,String>): Set<String> = buildSet {
    if (values["name"].isNullOrBlank()) add("name")
    listOf("price" to 0, "quantity" to 1, "max_per_order" to 1).forEach { (key,min) ->
        val raw = values[key].orEmpty().trim()
        if (key != "max_per_order" || raw.isNotEmpty()) {
            val number = raw.toIntOrNull()
            if (number == null || number < min) add(key)
        }
    }
    val start = runCatching { Instant.parse(values["sales_start"].orEmpty()) }.getOrNull()
    val end = runCatching { Instant.parse(values["sales_end"].orEmpty()) }.getOrNull()
    if (start == null) add("sales_start")
    if (end == null || (start != null && end <= start)) add("sales_end")
}

class CreateTicketTypeViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(TicketTypeFormState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var sequence = 0
    private var listSequence = 0
    fun setHidden(ticket: TicketType, hidden: Boolean) {
        val state = mutable.value
        if (ticket.id in state.hiding || ticket.id in state.hideUnknown || state.saving) return
        val expected = session.generation
        mutable.value = state.copy(hiding = state.hiding + ticket.id, hideErrors = state.hideErrors - ticket.id)
        viewModelScope.launch {
            try {
                val result = requests.request { token ->
                    val response = client.patch("$baseUrl/ticket_types/${ticket.id}/${if(hidden) "hide" else "unhide"}") { bearerAuth(token) }
                    if(response.status.value != 200) throw OrdersApiException(response.status.value,null,"Ticket visibility failed")
                    response.body<TicketType>()
                }
                if(expected != session.generation) return@launch
                require(result.id == ticket.id && result.eventId == ticket.eventId)
                val current = mutable.value
                mutable.value = current.copy(hiding = current.hiding - ticket.id,
                    added = current.added + (ticket.eventId to (current.added[ticket.eventId].orEmpty().filterNot { it.id == ticket.id } + result)))
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected == session.generation) mutable.value = mutable.value.copy(hiding = mutable.value.hiding - ticket.id,
                    hideErrors = mutable.value.hideErrors + ticket.id,
                    hideUnknown = if(error is OrdersApiException) mutable.value.hideUnknown else mutable.value.hideUnknown + ticket.id)
            }
        }
    }
    fun checkEdited() {
        val current = mutable.value
        val ticket = current.editing ?: return
        val expected = session.generation
        mutable.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                val actual = requests.request { token ->
                    val response = client.get("$baseUrl/events/${ticket.eventId}/ticket-types") { bearerAuth(token) }
                    if(response.status.value != 200) throw OrdersApiException(response.status.value,null,"Check unavailable")
                    response.body<List<TicketType>>().first { it.id == ticket.id }
                }
                if(expected != session.generation) return@launch
                edit(actual)
                mutable.value = mutable.value.copy(values = current.values, added = current.added +
                    (actual.eventId to (current.added[actual.eventId].orEmpty().filterNot { it.id == actual.id } + actual)))
            } catch(error: Exception) {
                if(error is CancellationException) throw error
                if(expected == session.generation) mutable.value = current.copy(saving = false,failed = true)
            }
        }
    }
    fun list(eventId: Int): List<TicketType> =
        (mutable.value.added[eventId].orEmpty() + mutable.value.loaded[eventId].orEmpty()).distinctBy { it.id }
    fun load(eventId: Int) {
        val expected = session.generation
        val requestId = ++listSequence
        mutable.value = mutable.value.copy(listLoading = true, listError = false)
        viewModelScope.launch {
            try {
                val result = requests.request { token ->
                    val response = client.get("$baseUrl/events/$eventId/ticket-types") { bearerAuth(token) }
                    if (response.status.value != 200) throw OrdersApiException(response.status.value,null,"Ticket list failed")
                    response.body<List<TicketType>>()
                }
                if (expected == session.generation && requestId == listSequence) mutable.value = mutable.value.copy(
                    listLoading = false, loaded = mutable.value.loaded + (eventId to result),
                    hideUnknown = mutable.value.hideUnknown - result.map { it.id }.toSet(),
                    added = mutable.value.added + (eventId to mutable.value.added[eventId].orEmpty().map { saved ->
                        if(saved.id in mutable.value.hideUnknown) result.firstOrNull { it.id == saved.id } ?: saved else saved
                    }))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && requestId == listSequence) mutable.value = mutable.value.copy(listLoading = false,listError = true)
            }
        }
    }
    fun edit(ticket: TicketType) {
        if(ticket.id in mutable.value.hiding || ticket.id in mutable.value.hideUnknown) return
        val values = mapOf("name" to ticket.name,"description" to ticket.description.orEmpty(),"price" to ticket.price.toString(),
            "quantity" to ticket.quantity.toString(),"max_per_order" to ticket.maxPerOrder.toString(),
            "sales_start" to ticket.salesStart,"sales_end" to ticket.salesEnd)
        sequence++
        mutable.value = mutable.value.copy(open = true,eventId = ticket.eventId,editing = ticket,values = values,original = values,
            errors = emptySet(),failed = false,unknown = false,belowSold = false)
    }
    init { viewModelScope.launch { session.tokens.collect {
        if (generation != session.generation) { generation = session.generation; sequence++; mutable.value = TicketTypeFormState() }
    } } }
    fun open(eventId: Int) { sequence++; mutable.value = mutable.value.copy(eventId = eventId, open = true, editing = null, values = emptyMap(), original = emptyMap(),errors = emptySet(),unknown = false,failed = false) }
    fun close() { if (!mutable.value.saving) { sequence++; mutable.value = mutable.value.copy(open = false, values = emptyMap(), errors = emptySet()) } }
    fun update(key: String, value: String) { if (!mutable.value.saving) mutable.value = mutable.value.copy(values = mutable.value.values + (key to value), errors = mutable.value.errors - key) }
    fun save() {
        val current = mutable.value
        val id = current.eventId ?: return
        if (current.saving || current.unknown) return
        val errors = validateTicketType(current.values)
        if (errors.isNotEmpty()) { mutable.value = current.copy(errors = errors); return }
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(saving = true, failed = false, errors = emptySet())
        val payload = buildJsonObject {
            current.values.forEach { (key,raw) ->
                val value = raw.trim()
                if (current.editing != null && value == current.original[key]) return@forEach
                when {
                    key == "max_per_order" && value.isEmpty() -> Unit
                    key in listOf("price","quantity","max_per_order") -> put(key,value.toInt())
                    key == "description" && value.isEmpty() -> put(key,JsonNull)
                    else -> put(key,value)
                }
            }
        }
        viewModelScope.launch {
            try {
                val ticket = requests.request { token ->
                    val response = client.request(if (current.editing == null) "$baseUrl/events/$id/ticket-type" else "$baseUrl/ticket-types/${current.editing.id}") {
                        method = if(current.editing == null) HttpMethod.Post else HttpMethod.Patch
                        bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload)
                    }
                    if (response.status.value == (if(current.editing == null) 201 else 200)) response.body<TicketType>() else {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        val fields = (body?.get("fields") as? JsonObject)?.keys.orEmpty() +
                            (body?.get("detail") as? JsonArray).orEmpty().mapNotNull { it.jsonObject["loc"]?.jsonArray?.lastOrNull()?.jsonPrimitive?.content }
                        if (expected == session.generation && requestId == sequence) mutable.value = mutable.value.copy(
                            errors = fields.toSet() + if(body?.get("error")?.jsonPrimitive?.content == "QuantityBelowSoldError") setOf("quantity") else emptySet(),
                            belowSold = body?.get("error")?.jsonPrimitive?.content == "QuantityBelowSoldError")
                        throw OrdersApiException(response.status.value,body?.get("error")?.jsonPrimitive?.content,"Ticket type request failed")
                    }
                }
                if (expected == session.generation && requestId == sequence) {
                    require(ticket.eventId == id)
                    mutable.value = mutable.value.copy(saving = false, open = false, values = emptyMap(),
                        added = mutable.value.added + (id to (mutable.value.added[id].orEmpty().filterNot { it.id == ticket.id } + ticket)))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && requestId == sequence)
                    mutable.value = mutable.value.copy(saving = false, failed = true, unknown = error !is OrdersApiException)
            }
        }
    }
}

@Composable
fun CreateTicketTypePage(vm: CreateTicketTypeViewModel) {
    val state by vm.state.collectAsState()
    val labels = listOf("name" to Res.string.ticket_create_name, "description" to Res.string.create_description,
        "price" to Res.string.ticket_create_price, "quantity" to Res.string.ticket_create_quantity,
        "sales_start" to Res.string.ticket_create_start, "sales_end" to Res.string.ticket_create_end,
        "max_per_order" to Res.string.ticket_create_limit)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = vm::close, enabled = !state.saving) { Text(stringResource(Res.string.ticket_create_back)) }
        Text(stringResource(if(state.editing == null) Res.string.ticket_create_title else Res.string.ticket_edit), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.event_offsets))
        labels.forEach { (key,label) ->
            if (key.startsWith("sales_")) EventDateInput(stringResource(label), state.values[key].orEmpty(), !state.saving) { vm.update(key,it) }
            else OutlinedTextField(value = state.values[key].orEmpty(), onValueChange = { vm.update(key,it) },
                label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), enabled = !state.saving,
                isError = key in state.errors, singleLine = key != "description")
            if (key in state.errors) Text(stringResource(if(key == "quantity" && state.belowSold) Res.string.ticket_below_sold else Res.string.ticket_create_invalid), color = MaterialTheme.colorScheme.error)
        }
        if (state.failed) Text(stringResource(if (state.unknown) Res.string.ticket_create_unknown else Res.string.create_failed), color = MaterialTheme.colorScheme.error)
        if (!state.unknown) Button(onClick = vm::save, enabled = !state.saving) { Text(stringResource(if(state.editing == null) Res.string.ticket_create_save else Res.string.event_save_changes)) }
        if (state.unknown && state.editing != null) Button(onClick = vm::checkEdited, enabled = !state.saving) {
            Text(stringResource(Res.string.payment_check_status))
        }
        if(state.editing != null && !state.unknown && state.original != state.values) {
            Text(stringResource(Res.string.event_compare))
            labels.filter { (key,_) -> state.original[key] != state.values[key] }.forEach { (key,label) ->
                Text("${stringResource(label)}: ${state.original[key].orEmpty()} → ${state.values[key].orEmpty()}")
            }
        }
        if (state.saving) CircularProgressIndicator()
    }
}
