package com.example.biletflow
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.assertEquals
/** Configure valid multi-ticket/available inventory through the organizer API, not by bypassing sales rules. */
suspend fun prepareTicketFixture(client:HttpClient) {
    assertEquals(200,client.patch("http://test/ticket-types/1002") {bearerAuth("mock-access-token");contentType(ContentType.Application.Json);setBody("""{"max_per_order":2}""")}.status.value)
    assertEquals(200,client.patch("http://test/ticket-types/2001") {bearerAuth("mock-access-token");contentType(ContentType.Application.Json);setBody("""{"quantity":210}""")}.status.value)
}
