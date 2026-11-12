package com.example.biletflow.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: Int,
    @SerialName("organizer_id") val organizerId: Int,
    val title: String,
    val description: String? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val status: String,
    val category: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_address") val venueAddress: String? = null,
    val capacity: Int? = null,
    val visibility: String,
    @SerialName("registration_opens_at") val registrationOpensAt: String? = null,
    @SerialName("registration_closes_at") val registrationClosesAt: String? = null,
    @SerialName("refund_policy") val refundPolicy: String? = null,
)
