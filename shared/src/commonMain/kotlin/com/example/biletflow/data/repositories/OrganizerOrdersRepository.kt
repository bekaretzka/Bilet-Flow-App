package com.example.biletflow.data.repositories

import com.example.biletflow.domain.models.Order
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class OrganizerRefund(
    val amount: Long, val reason: String?,
    @SerialName("provider_reference") val providerReference: String,
    @SerialName("created_at") val createdAt: String,
)

/** Event context belongs to the route, not to the backend's EventOrderRow. */
@Serializable
data class OrganizerOrder(
    val id: Int, val status: String,
    @SerialName("total_amount") val totalAmount: Long,
    @SerialName("buyer_name") val buyerName: String,
    @SerialName("buyer_email") val buyerEmail: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("paid_at") val paidAt: String?,
    @SerialName("cancelled_at") val cancelledAt: String?,
    @SerialName("refunded_at") val refundedAt: String?,
    @SerialName("discount_amount") val discountAmount: Long,
    @SerialName("promo_code") val promoCode: String?,
    @SerialName("processing_fee") val processingFee: Long,
    val tickets: Int,
    @SerialName("payment_reference") val paymentReference: String?,
    val refund: OrganizerRefund?,
    @SerialName("is_simulated") val isSimulated: Boolean,
) {
    val refundable: Boolean get() = status == "confirmed" && totalAmount > 0 && paidAt != null
}

@Serializable
data class OrganizerOrdersPage(val items: List<OrganizerOrder>, val total: Int)

class OrganizerOrdersRepository(private val client: HttpClient, private val requests: SessionOrdersRepository, private val baseUrl: String) {
    suspend fun list(eventId: Int, offset: Int = 0, status: String? = null, q: String = "", limit: Int = 50): OrganizerOrdersPage = requests.request { token ->
        val response = client.get("$baseUrl/events/$eventId/orders") {
            bearerAuth(token); parameter("limit", limit); parameter("offset", offset)
            status?.let { parameter("status", it) }
            q.trim().takeIf { it.isNotEmpty() }?.let { parameter("q", it) }
        }
        response.requireSuccess()
        response.body<OrganizerOrdersPage>().also { require(it.total >= 0) }
    }

    // q searches buyer name/email, not order ID. Walk unfiltered pages to find an exact ID.
    suspend fun get(eventId: Int, orderId: Int): OrganizerOrder {
        var offset = 0
        do {
            val page = list(eventId, offset = offset)
            page.items.firstOrNull { it.id == orderId }?.let { return it }
            if (page.items.isEmpty()) break
            offset += page.items.size
        } while (offset < page.total)
        throw OrdersApiException(404, "OrderNotFoundError", "Order is absent from this event's order list")
    }

    suspend fun refund(eventId: Int, orderId: Int, reason: String): Order {
        return requests.refundOrder(orderId, reason, "").also { require(it.eventId == eventId && it.id == orderId) }
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value == 200) return
        val payload = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
        throw OrdersApiException(status.value, payload?.get("error")?.jsonPrimitive?.contentOrNull,
            (payload?.get("message") ?: payload?.get("detail"))?.toString() ?: "HTTP ${status.value}")
    }
}
