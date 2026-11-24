package com.example.biletflow.data.repositories

import com.example.biletflow.domain.models.Order
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class OrganizerOrder(
    val id: Int,
    @SerialName("event_id") val eventId: Int,
    val status: String,
    @SerialName("total_amount") val totalAmount: Long,
    @SerialName("buyer_name") val buyerName: String,
    @SerialName("buyer_email") val buyerEmail: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("paid_at") val paidAt: String?,
    @SerialName("refunded_at") val refundedAt: String?,
) {
    val refundable: Boolean get() = status == "confirmed" && totalAmount > 0 && paidAt != null
}

@Serializable
data class OrganizerOrdersPage(val items: List<OrganizerOrder>, @SerialName("next_cursor") val nextCursor: String?)

class OrganizerOrdersRepository(private val client: HttpClient, private val requests: SessionOrdersRepository, private val baseUrl: String) {
    suspend fun list(eventId: Int, cursor: String? = null, status: String? = null): OrganizerOrdersPage = requests.request { token ->
        val response = client.get("$baseUrl/organizer/events/$eventId/orders") {
            bearerAuth(token); parameter("limit", 50)
            cursor?.let { parameter("cursor", it) }; status?.let { parameter("status", it) }
        }
        response.requireSuccess()
        response.body<OrganizerOrdersPage>().also { page -> require(page.items.all { it.eventId == eventId }) }
    }

    suspend fun get(eventId: Int, orderId: Int): OrganizerOrder = requests.request { token ->
        val response = client.get("$baseUrl/organizer/events/$eventId/orders/$orderId") { bearerAuth(token) }
        response.requireSuccess()
        response.body<OrganizerOrder>().also { require(it.eventId == eventId && it.id == orderId) }
    }

    suspend fun refund(eventId: Int, orderId: Int, reason: String): OrganizerOrder {
        val order: Order = requests.refundOrder(orderId, reason, "")
        require(order.eventId == eventId && order.id == orderId)
        return OrganizerOrder(order.id, order.eventId, order.status, order.totalAmount, order.buyerName,
            order.buyerEmail, order.createdAt, order.paidAt, order.refundedAt)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value == 200) return
        val payload = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
        throw OrdersApiException(status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull,
            (payload?.get("message") ?: payload?.get("detail"))?.toString() ?: "HTTP ${status.value}")
    }
}
