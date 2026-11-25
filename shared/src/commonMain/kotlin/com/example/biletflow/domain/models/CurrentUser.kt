package com.example.biletflow.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CurrentOrganizer(val id: Int, @SerialName("user_id") val userId: Int, val status: String)
@Serializable
data class CurrentUser(val id: Int, val email: String,
    @SerialName("email_verified") val emailVerified: Boolean,
    @SerialName("is_platform_admin") val isPlatformAdmin: Boolean,
    val organizer: CurrentOrganizer?, @SerialName("is_event_staff") val isEventStaff: Boolean) {
    val canScan: Boolean get() = isEventStaff || organizer?.status == "approved"
    fun owns(event: Event): Boolean = organizer?.status == "approved" && organizer.id == event.organizerId
}
