package com.example.biletflow.presentation.organizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

val eventDateFields = listOf("start_time", "end_time", "registration_opens_at", "registration_closes_at")
val eventTextFields = listOf("title", "description", "category", "venue_name", "venue_address", "capacity", "refund_policy")
data class CreateEventState(val open: Boolean = false, val values: Map<String,String> = emptyMap(),
    val errors: Map<String,String> = emptyMap(), val saving: Boolean = false,
    val failed: Boolean = false, val unknown: Boolean = false, val denied: Boolean = false,
    val created: Event? = null, val editingId: Int? = null, val original: Map<String,String> = emptyMap(),
    val copyingFromId: Int? = null)

class CreateEventViewModel(private val client: HttpClient, private val requests: SessionOrdersRepository,
    private val session: SessionStore, private val baseUrl: String = apiBaseUrl) : ViewModel() {
    private val mutable = MutableStateFlow(CreateEventState())
    val state = mutable.asStateFlow()
    private var generation = session.generation
    private var sequence = 0
    init { viewModelScope.launch { session.tokens.collect {
        if (generation != session.generation) { generation = session.generation; reset() }
    } } }
    fun open() { sequence++; mutable.value = CreateEventState(open = true, values = mapOf("visibility" to "public")) }
    fun edit(event: Event) {
        sequence++
        val values = mapOf("title" to event.title, "description" to event.description.orEmpty(),
            "category" to event.category.orEmpty(), "venue_name" to event.venueName.orEmpty(),
            "venue_address" to event.venueAddress.orEmpty(), "capacity" to (event.capacity?.toString() ?: ""),
            "visibility" to event.visibility, "refund_policy" to event.refundPolicy.orEmpty(),
            "start_time" to event.startTime, "end_time" to event.endTime,
            "registration_opens_at" to event.registrationOpensAt.orEmpty(),
            "registration_closes_at" to event.registrationClosesAt.orEmpty())
        mutable.value = CreateEventState(open = true, values = values, original = values, editingId = event.id)
    }
    fun copySettings(event: Event) {
        edit(event)
        mutable.value = CreateEventState(open = true, values = mutable.value.values - eventDateFields.toSet(), copyingFromId = event.id)
    }
    fun reset() { sequence++; mutable.value = CreateEventState() }
    fun close() { if (!mutable.value.saving) reset() }
    fun checkActual() {
        val current = mutable.value
        val id = current.editingId ?: return
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                val events = requests.request { token ->
                    val response = client.get("$baseUrl/events/me") { bearerAuth(token) }
                    if (response.status.value != 200) throw OrdersApiException(response.status.value,null,"Check failed")
                    response.body<List<Event>>()
                }
                if (expected != session.generation || requestId != sequence) return@launch
                val actual = events.first { it.id == id }
                edit(actual)
                val baseline = mutable.value.original
                mutable.value = mutable.value.copy(values = current.values, original = baseline, unknown = false, failed = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected == session.generation && requestId == sequence) mutable.value = current.copy(saving = false, failed = true)
            }
        }
    }
    fun update(key: String, value: String) {
        if (!mutable.value.saving) mutable.value = mutable.value.copy(
            values = mutable.value.values + (key to value), errors = mutable.value.errors - key,
        )
    }
    fun save() {
        val current = mutable.value
        if (current.saving || current.unknown || current.created != null) return
        val errors = validateEventForm(current.values)
        if (errors.isNotEmpty()) { mutable.value = current.copy(errors = errors); return }
        val expected = session.generation
        val requestId = ++sequence
        mutable.value = current.copy(saving = true, failed = false, errors = emptyMap())
        val payload = buildJsonObject {
            (eventTextFields + eventDateFields + "visibility").forEach { key ->
                val value = current.values[key].orEmpty().trim()
                if (current.editingId != null && value == current.original[key]) return@forEach
                if (current.editingId != null && value.isEmpty()) { put(key, JsonNull); return@forEach }
                if (value.isNotEmpty()) {
                    if (key == "capacity") put(key, value.toInt()) else put(key, value)
                }
            }
        }
        viewModelScope.launch {
            try {
                val event = requests.request { token ->
                    val response = client.request(if (current.editingId == null) "$baseUrl/events" else "$baseUrl/events/${current.editingId}") {
                        method = if (current.editingId == null) HttpMethod.Post else HttpMethod.Patch
                        bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload)
                    }
                    if (response.status.value == 200) response.body<Event>() else {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        val fields = parseEventFieldErrors(body)
                        if (fields.isNotEmpty() && expected == session.generation && requestId == sequence)
                            mutable.value = mutable.value.copy(errors = fields)
                        throw OrdersApiException(response.status.value, body?.get("error")?.jsonPrimitive?.content, "Create event failed")
                    }
                }
                if (current.copyingFromId != null) require(event.id != current.copyingFromId && event.status == "draft")
                if (expected == session.generation && requestId == sequence) mutable.value = mutable.value.copy(saving = false, open = false, created = event)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (expected != session.generation || requestId != sequence) return@launch
                val api = error as? OrdersApiException
                mutable.value = mutable.value.copy(saving = false, failed = true, unknown = api == null,
                    denied = api?.errorCode in listOf("OrganizerNotFoundError", "OrganizerNotApprovedError"))
            }
        }
    }
}

fun validateEventForm(values: Map<String,String>): Map<String,String> = buildMap {
    if (values["title"].isNullOrBlank()) put("title", "required")
    val dates = eventDateFields.associateWith { field ->
        val value = values[field].orEmpty().trim()
        val parsed = if (value.isEmpty()) null else runCatching { Instant.parse(value) }.getOrNull()
        if (field in listOf("start_time", "end_time") && value.isEmpty()) put(field, "required")
        else if (value.isNotEmpty() && (parsed == null || !(value.endsWith("Z") || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(value)))) put(field, "date")
        parsed
    }
    if (dates["start_time"] != null && dates["end_time"] != null && dates.getValue("end_time")!! <= dates.getValue("start_time")!!) put("end_time", "order")
    if (dates["registration_opens_at"] != null && dates["registration_closes_at"] != null && dates.getValue("registration_closes_at")!! <= dates.getValue("registration_opens_at")!!) put("registration_closes_at", "order")
    val capacity = values["capacity"].orEmpty().trim()
    if (capacity.isNotEmpty() && (capacity.toIntOrNull() == null || capacity.toInt() < 1)) put("capacity", "number")
    if (values["refund_policy"].orEmpty().length > 2000) put("refund_policy", "length")
}

private fun parseEventFieldErrors(body: JsonObject?): Map<String,String> = buildMap {
    (body?.get("detail") as? JsonArray)?.forEach { item ->
        val entry = item.jsonObject
        val field = entry["loc"]?.jsonArray?.lastOrNull()?.jsonPrimitive?.content
        if (field != null) put(field, entry["msg"]?.jsonPrimitive?.content ?: "invalid")
    }
    (body?.get("fields") as? JsonObject)?.forEach { (field, _) -> put(field, "invalid") }
}
