package com.example.biletflow.data.repositories

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class SupportCreate(
    @SerialName("event_id") val eventId: Int,
    @SerialName("order_id") val orderId: Int? = null,
    @SerialName("ticket_id") val ticketId: Int? = null,
    val category: String, val subject: String, val message: String,
    @SerialName("client_request_id") val clientRequestId: String,
)
@Serializable
data class SupportRequest(
    val id: Int, val status: String,
    @SerialName("event_id") val eventId: Int,
    @SerialName("order_id") val orderId: Int?,
    @SerialName("ticket_id") val ticketId: Int?,
    val category: String, val subject: String, val message: String,
    @SerialName("client_request_id") val clientRequestId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String = createdAt,
    @SerialName("event_title") val eventTitle: String? = null,
    @SerialName("author_id") val authorId: Int? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("author_email") val authorEmail: String? = null,
    @SerialName("can_manage_status") val canManageStatus: Boolean = false,
)
@Serializable
data class SupportRequestsPage(val items: List<SupportRequest>, @SerialName("next_cursor") val nextCursor: String?)
@Serializable
data class SupportMessage(val id: Int, @SerialName("request_id") val requestId: Int,
    @SerialName("author_name") val authorName: String?, @SerialName("author_role") val authorRole: String,
    val text: String, @SerialName("created_at") val createdAt: String)
@Serializable
data class SupportMessagesPage(val items: List<SupportMessage>, @SerialName("next_cursor") val nextCursor: String?)
@Serializable
data class SupportMessageCreate(val body: String, @SerialName("client_request_id") val clientRequestId: String)
@Serializable
data class SupportStatusChange(val status: String, @SerialName("expected_status") val expectedStatus: String)
class SupportApiException(val statusCode: Int, val code: String?, val fields: Set<String>, override val message: String) : Exception(message)

/** Historical proposed contract, retained for legacy thread code/tests only.
 * App uses SupportCasesRepository and SupportCasesViewModel; no live screen calls these routes. */
class SupportRepository(private val client: HttpClient, private val requests: SessionOrdersRepository, private val baseUrl: String) {
    suspend fun changeStatus(id: Int, payload: SupportStatusChange): SupportRequest = requests.request { token ->
        val response = client.patch("$baseUrl/support/requests/$id/status") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload)
        }
        if(response.status.value != 200) {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            throw OrdersApiException(response.status.value,(body?.get("error") as? JsonPrimitive)?.contentOrNull,
                (body?.get("message") ?: body?.get("detail"))?.toString() ?: "HTTP ${response.status.value}")
        }
        response.body<SupportRequest>().also { require(it.id == id) }
    }
    private suspend inline fun <reified T> read(path: String, cursor: String? = null, limit: Int? = null, afterId: Int? = null): T = requests.request { token ->
        val response = client.get("$baseUrl$path") { bearerAuth(token); cursor?.let { parameter("cursor",it) }; limit?.let { parameter("limit",it) }; afterId?.let { parameter("after_id",it) } }
        if (response.status.value != 200) {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            throw OrdersApiException(response.status.value, (body?.get("error") as? JsonPrimitive)?.contentOrNull,
                (body?.get("message") ?: body?.get("detail"))?.toString() ?: "HTTP ${response.status.value}")
        }
        response.body<T>()
    }
    suspend fun list(cursor: String? = null, limit: Int = 50): SupportRequestsPage = read("/support/requests/me",cursor,limit)
    suspend fun eventList(eventId: Int, cursor: String? = null, limit: Int = 50): SupportRequestsPage =
        read<SupportRequestsPage>("/organizer/events/$eventId/support/requests",cursor,limit).also { require(it.items.all { item -> item.eventId == eventId }) }
    suspend fun get(id: Int): SupportRequest = read<SupportRequest>("/support/requests/$id").also { require(it.id == id) }
    suspend fun messages(id: Int, cursor: String? = null, limit: Int = 50, afterId: Int? = null): SupportMessagesPage =
        read<SupportMessagesPage>("/support/requests/$id/messages",cursor,limit,afterId).also { require(it.items.all { item -> item.requestId == id }) }
    suspend fun send(id: Int, payload: SupportMessageCreate): SupportMessage = requests.request { token ->
        val response = client.post("$baseUrl/support/requests/$id/messages") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload) }
        if(response.status.value != 201) {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            throw OrdersApiException(response.status.value,(body?.get("error") as? JsonPrimitive)?.contentOrNull,
                (body?.get("message") ?: body?.get("detail"))?.toString() ?: "HTTP ${response.status.value}")
        }
        response.body<SupportMessage>().also { require(it.requestId == id && it.text == payload.body) }
    }
    suspend fun create(payload: SupportCreate): SupportRequest = requests.request { token ->
        val response = client.post("$baseUrl/support/requests") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(payload)
        }
        if (response.status.value == 201) response.body<SupportRequest>().also {
            require(it.eventId == payload.eventId && it.clientRequestId == payload.clientRequestId &&
                (payload.orderId == null || it.orderId == payload.orderId) && it.ticketId == payload.ticketId &&
                it.subject == payload.subject && it.message == payload.message && it.category == payload.category)
        } else {
            val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            val code = body?.get("error")?.jsonPrimitive?.contentOrNull
            val message = (body?.get("message") ?: body?.get("detail"))?.toString() ?: "HTTP ${response.status.value}"
            if (response.status.value == 401) throw OrdersApiException(401, code, message)
            val fields = (body?.get("fields") as? JsonObject)?.keys.orEmpty() +
                (body?.get("detail") as? JsonArray).orEmpty().mapNotNull { it.jsonObject["loc"]?.jsonArray?.lastOrNull()?.jsonPrimitive?.contentOrNull }
            throw SupportApiException(response.status.value, code, fields, message)
        }
    }
}
