package com.example.biletflow.data.repositories

import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.core.network.createHttpClient
import com.example.biletflow.domain.models.Event
import com.example.biletflow.domain.models.EventPage
import com.example.biletflow.domain.models.TicketType
import com.example.biletflow.domain.repositories.EventsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

class EventsApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

class EventsRepositoryImpl(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = apiBaseUrl,
) : EventsRepository {
    override suspend fun getEvents(): List<Event> {
        val result=linkedMapOf<Int,Event>()
        var offset=0
        do {
            val page=getEventsPage(limit=100,offset=offset)
            page.items.forEach { result[it.id]=it }; offset+=page.items.size
            if(page.items.isEmpty() || offset >= page.total) break
        } while(true)
        return result.values.toList()
    }
    override suspend fun getEventsPage(q: String?,category: String?,limit: Int,offset: Int): EventPage {
        val response=client.get("$baseUrl/events") {
            parameter("limit",limit); parameter("offset",offset)
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter("q",it) }
            category?.let { parameter("category",it) }
        }
        if(response.status.value != 200) throw EventsApiException(response.status.value,"HTTP ${response.status.value}: ${response.bodyAsText()}")
        return response.body<EventPage>().also { require(it.total >= 0 && it.items.size <= limit) }
    }
    override suspend fun getCategories(): List<String> {
        val response=client.get("$baseUrl/events/categories")
        if(response.status.value != 200) throw EventsApiException(response.status.value,"HTTP ${response.status.value}: ${response.bodyAsText()}")
        return response.body()
    }

    override suspend fun getEvent(eventId: Int): Event {
        val response = client.get("$baseUrl/events/$eventId")
        if (response.status.value !in 200..299) {
            throw EventsApiException(
                statusCode = response.status.value,
                message = "Event request failed (${response.status.value})",
            )
        }
        return response.body()
    }

    override suspend fun getTicketTypes(eventId: Int): List<TicketType> {
        val response = client.get("$baseUrl/events/$eventId/ticket-types")
        if (response.status.value !in 200..299) {
            throw EventsApiException(
                statusCode = response.status.value,
                message = "Ticket types request failed (${response.status.value})",
            )
        }
        return response.body()
    }
}
