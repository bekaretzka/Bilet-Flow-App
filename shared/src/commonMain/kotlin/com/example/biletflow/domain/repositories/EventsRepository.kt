package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.models.EventPage
import com.example.biletflow.domain.models.TicketType

interface EventsRepository {
    suspend fun getEvents(): List<Event>
    suspend fun getEventsPage(q: String? = null, category: String? = null, limit: Int = 20, offset: Int = 0): EventPage = error("Paged catalog is not implemented")
    suspend fun getCategories(): List<String> = emptyList()
    suspend fun getEvent(eventId: Int): Event
    suspend fun getTicketTypes(eventId: Int): List<TicketType>
}
