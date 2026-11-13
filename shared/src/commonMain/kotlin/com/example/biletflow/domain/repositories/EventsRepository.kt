package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.models.TicketType

interface EventsRepository {
    suspend fun getEvents(): List<Event>
    suspend fun getEvent(eventId: Int): Event
    suspend fun getTicketTypes(eventId: Int): List<TicketType>
}
