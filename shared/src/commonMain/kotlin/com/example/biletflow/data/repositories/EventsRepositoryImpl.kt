package com.example.biletflow.data.repositories

import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.core.network.createHttpClient
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.repositories.EventsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class EventsApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

class EventsRepositoryImpl(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = apiBaseUrl,
) : EventsRepository {
    override suspend fun getEvents(): List<Event> {
        val response = client.get("$baseUrl/events")
        if (response.status.value !in 200..299) {
            throw EventsApiException(
                statusCode = response.status.value,
                message = "Events request failed (${response.status.value})",
            )
        }
        return response.body()
    }
}
