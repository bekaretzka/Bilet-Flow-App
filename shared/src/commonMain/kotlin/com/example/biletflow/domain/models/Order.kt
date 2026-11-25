package com.example.biletflow.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrderItem(
    @SerialName("ticket_type_id") val ticketTypeId: Int,
    val quantity: Int,
    /** One per ticket; blank entries are replaced with buyer_name before sending. */
    @SerialName("attendee_names") val attendeeNames: List<String>? = null,
)

@Serializable
data class CreateOrderRequest(
    @SerialName("buyer_name") val buyerName: String,
    val items: List<CreateOrderItem>,
    @SerialName("promo_code") val promoCode: String? = null,
)

@Serializable
data class PreviewOrderItem(@SerialName("ticket_type_id") val ticketTypeId: Int, val quantity: Int)

@Serializable
data class OrderPreviewRequest(val items: List<PreviewOrderItem>, @SerialName("promo_code") val promoCode: String? = null)

@Serializable
data class OrderPreview(val subtotal: Long, val discount: Long, val total: Long, @SerialName("promo_code") val promoCode: String? = null)

@Serializable
data class OrderItem(
    val id: Int,
    @SerialName("ticket_type_id") val ticketTypeId: Int,
    val quantity: Int,
    @SerialName("unit_price") val unitPrice: Int,
)

@Serializable
data class Ticket(
    val id: Int,
    val code: String,
    @SerialName("ticket_type_id") val ticketTypeId: Int,
    @SerialName("event_id") val eventId: Int,
    @SerialName("attendee_name") val attendeeName: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("checked_in_at") val checkedInAt: String? = null,
)

@Serializable
data class Order(
    val id: Int,
    @SerialName("event_id") val eventId: Int,
    val status: String,
    @SerialName("total_amount") val totalAmount: Long,
    @SerialName("discount_amount") val discountAmount: Long = 0,
    @SerialName("promo_code") val promoCode: String? = null,
    @SerialName("buyer_name") val buyerName: String,
    @SerialName("buyer_email") val buyerEmail: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("refunded_at") val refundedAt: String? = null,
    val items: List<OrderItem> = emptyList(),
    val tickets: List<Ticket> = emptyList(),
    /** OrderEvent: enough to show the order even after the event is cancelled or suspended. */
    val event: OrderEvent? = null,
)

@Serializable
data class OrderEvent(
    val id: Int,
    val title: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_address") val venueAddress: String? = null,
    val status: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
)
