package com.example.biletflow.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TicketType(
    val id: Int,
    @SerialName("event_id") val eventId: Int,
    val name: String,
    val description: String? = null,
    val price: Int,
    val quantity: Int,
    @SerialName("sales_start") val salesStart: String,
    @SerialName("sales_end") val salesEnd: String,
    @SerialName("is_hidden") val isHidden: Boolean,
    @SerialName("max_per_order") val maxPerOrder: Int,
    @SerialName("sold_count") val soldCount: Int,
)
