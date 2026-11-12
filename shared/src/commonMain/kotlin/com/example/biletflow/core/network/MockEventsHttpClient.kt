package com.example.biletflow.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createMockEventsHttpClient(): HttpClient = HttpClient(
    MockEngine { request ->
        check(request.method == HttpMethod.Get && request.url.encodedPath == "/events") {
            "Unexpected mock request: ${request.method.value} ${request.url}"
        }
        respond(
            content = MOCK_EVENTS_JSON,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

private const val MOCK_EVENTS_JSON = """
[
  {
    "id": 101,
    "organizer_id": 11,
    "title": "Инди-вечер в Астане",
    "description": "Живой концерт локальных групп.",
    "start_time": "2026-12-05T19:00:00+05:00",
    "end_time": "2026-12-05T22:00:00+05:00",
    "status": "published",
    "category": "Концерт",
    "venue_name": "Конгресс-центр",
    "venue_address": "проспект Сауран, 44",
    "capacity": 800,
    "visibility": "public",
    "registration_opens_at": "2026-11-15T09:00:00+05:00",
    "registration_closes_at": "2026-12-05T18:30:00+05:00",
    "refund_policy": "Возврат возможен не позднее чем за 24 часа."
  },
  {
    "id": 102,
    "organizer_id": 12,
    "title": "Город будущего",
    "description": "Открытая лекция об устойчивой архитектуре.",
    "start_time": "2026-12-12T18:30:00+05:00",
    "end_time": "2026-12-12T20:00:00+05:00",
    "status": "published",
    "category": "Лекция",
    "venue_name": "Национальная библиотека",
    "venue_address": "проспект Мәңгілік Ел, 11",
    "capacity": 200,
    "visibility": "public",
    "registration_opens_at": null,
    "registration_closes_at": null,
    "refund_policy": null
  },
  {
    "id": 103,
    "organizer_id": 13,
    "title": "Зимний забег",
    "description": null,
    "start_time": "2026-12-20T10:00:00+05:00",
    "end_time": "2026-12-20T13:00:00+05:00",
    "status": "published",
    "category": "Спорт",
    "venue_name": "Ботанический сад",
    "venue_address": null,
    "capacity": null,
    "visibility": "public",
    "registration_opens_at": null,
    "registration_closes_at": null,
    "refund_policy": null
  },
  {
    "id": 104,
    "organizer_id": 14,
    "title": "Открытая встреча сообщества",
    "description": null,
    "start_time": "2026-12-27T15:00:00+05:00",
    "end_time": "2026-12-27T16:30:00+05:00",
    "status": "published",
    "category": null,
    "venue_name": null,
    "venue_address": null,
    "capacity": null,
    "visibility": "public",
    "registration_opens_at": null,
    "registration_closes_at": null,
    "refund_policy": null
  }
]
"""
