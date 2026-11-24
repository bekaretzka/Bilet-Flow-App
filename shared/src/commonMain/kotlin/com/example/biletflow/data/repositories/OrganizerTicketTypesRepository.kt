package com.example.biletflow.data.repositories

import com.example.biletflow.domain.models.TicketType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable
private data class TicketTypePage(val items: List<TicketType>, @SerialName("next_cursor") val nextCursor: String?)

class OrganizerTicketTypesRepository(
    private val client: HttpClient,
    private val requests: SessionOrdersRepository,
    private val baseUrl: String,
) {
    suspend fun list(eventId: Int): List<TicketType> {
        val result = linkedMapOf<Int, TicketType>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = requests.request { token ->
                val response = client.get("$baseUrl/organizer/events/$eventId/ticket-types") {
                    bearerAuth(token); parameter("limit", 50)
                    cursor?.let { parameter("cursor", it) }
                }
                response.requireSuccess()
                response.body<TicketTypePage>()
            }
            require(page.items.all { it.eventId == eventId }) { "Ticket type belongs to another event" }
            page.items.forEach { result[it.id] = it }
            cursor = page.nextCursor
            if (cursor != null) check(cursors.add(cursor)) { "Repeated pagination cursor" }
        } while (cursor != null)
        return result.values.sortedBy { it.id }
    }

    suspend fun get(eventId: Int, ticketId: Int): TicketType = requests.request { token ->
        val response = client.get("$baseUrl/organizer/events/$eventId/ticket-types/$ticketId") { bearerAuth(token) }
        response.requireSuccess()
        response.body<TicketType>().also { require(it.eventId == eventId && it.id == ticketId) }
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value == 200) return
        val payload = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
        throw OrdersApiException(status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull,
            (payload?.get("message") ?: payload?.get("detail"))?.toString() ?: "HTTP ${status.value}")
    }
}
