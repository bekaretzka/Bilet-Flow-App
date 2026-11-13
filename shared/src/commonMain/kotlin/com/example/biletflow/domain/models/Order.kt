package com.example.biletflow.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrderItem(
    @SerialName("ticket_type_id") val ticketTypeId: Int,
    val quantity: Int,
)

@Serializable
data class CreateOrderRequest(
    @SerialName("buyer_name") val buyerName: String,
    val items: List<CreateOrderItem>,
)

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
    @SerialName("buyer_name") val buyerName: String,
    @SerialName("buyer_email") val buyerEmail: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("refunded_at") val refundedAt: String? = null,
    val items: List<OrderItem> = emptyList(),
    val tickets: List<Ticket> = emptyList(),
)
