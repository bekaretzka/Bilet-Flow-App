package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.Event

interface EventsRepository {
    suspend fun getEvents(): List<Event>
}
