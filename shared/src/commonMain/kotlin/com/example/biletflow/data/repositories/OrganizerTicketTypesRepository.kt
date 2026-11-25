package com.example.biletflow.data.repositories

import com.example.biletflow.domain.models.TicketType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class OrganizerTicketTypesRepository(
    private val client: HttpClient,
    private val requests: SessionOrdersRepository,
    private val baseUrl: String,
) {
    suspend fun list(eventId: Int): List<TicketType> = requests.request { token ->
        val response = client.get("$baseUrl/events/$eventId/ticket-types/manage") { bearerAuth(token) }
        response.requireSuccess()
        response.body<List<TicketType>>().also { types ->
            require(types.all { it.eventId == eventId }) { "Ticket type belongs to another event" }
        }
    }

    // The backend has no organizer GET for one type. Verify against the complete server list.
    suspend fun get(eventId: Int, ticketId: Int): TicketType = list(eventId).firstOrNull { it.id == ticketId }
        ?: throw OrdersApiException(404, "TicketTypeNotFoundError", "Ticket type is absent from this event's manage list")

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value == 200) return
        val payload = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
        throw OrdersApiException(status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull,
            (payload?.get("message") ?: payload?.get("detail"))?.toString() ?: "HTTP ${status.value}")
    }
}
