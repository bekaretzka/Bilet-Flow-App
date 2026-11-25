package com.example.biletflow.data.repositories

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class CampaignCreate(
    val name: String, val code: String,
    @SerialName("discount_type") val discountType: String,
    @SerialName("discount_value") val discountValue: Long,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("usage_limit") val usageLimit: Int? = null,
    @SerialName("ticket_type_ids") val ticketTypeIds: List<Int> = emptyList(),
    @SerialName("client_request_id") val clientRequestId: String,
)
@Serializable
data class Campaign(
    val id: Int, @SerialName("event_id") val eventId: Int,
    val name: String, val code: String,
    @SerialName("discount_type") val discountType: String,
    @SerialName("discount_value") val discountValue: Long,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("usage_limit") val usageLimit: Int?,
    @SerialName("ticket_type_ids") val ticketTypeIds: List<Int>,
    @SerialName("client_request_id") val clientRequestId: String,
    @SerialName("created_at") val createdAt: String,
)
@Serializable
data class CampaignPage(val items: List<Campaign>, @SerialName("next_cursor") val nextCursor: String?)
class CampaignApiException(val status: Int, val code: String?, val fields: Set<String>, override val message: String): Exception(message)

fun validateCampaign(input: CampaignCreate): Set<String> = buildSet {
    val length = input.name.indices.count { it == 0 || !input.name[it].isLowSurrogate() || !input.name[it-1].isHighSurrogate() }
    if(input.name != input.name.trim() || length !in 1..200) add("name")
    if(!Regex("[A-Z0-9][A-Z0-9_-]{2,31}").matches(input.code)) add("code")
    if(input.discountType !in listOf("percent","fixed")) add("discount_type")
    if(input.discountValue !in 1L..(if(input.discountType == "percent") 100L else 1_000_000_000L)) add("discount_value")
    val start=runCatching { Instant.parse(input.startsAt) }.getOrNull()
    val end=runCatching { Instant.parse(input.endsAt) }.getOrNull()
    if(start == null) add("starts_at")
    if(end == null || start != null && end <= start) add("ends_at")
    if(input.usageLimit != null && input.usageLimit !in 1..1_000_000) add("usage_limit")
    if(input.ticketTypeIds.size > 100 || input.ticketTypeIds.any { it <= 0 } || input.ticketTypeIds.distinct().size != input.ticketTypeIds.size) add("ticket_type_ids")
    if(!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}").matches(input.clientRequestId)) add("client_request_id")
}

class CampaignsRepository(private val client: HttpClient,private val requests: SessionOrdersRepository,private val baseUrl: String) {
    private suspend fun io.ktor.client.statement.HttpResponse.check(expected: Int) {
        if(status.value == expected) return
        val body=runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
        val code=(body?.get("error") as? JsonPrimitive)?.contentOrNull
        val message=(body?.get("message") ?: body?.get("detail"))?.toString() ?: "HTTP ${status.value}"
        if(status.value == 401) throw OrdersApiException(401,code,message)
        val fields=(body?.get("fields") as? JsonObject)?.keys.orEmpty() + (body?.get("detail") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("loc")?.jsonArray?.lastOrNull()?.jsonPrimitive?.contentOrNull }
        throw CampaignApiException(status.value,code,fields,message)
    }
    suspend fun list(eventId: Int,cursor: String? = null,limit: Int = 50): CampaignPage = requests.request { token ->
        val response=client.get("$baseUrl/organizer/events/$eventId/campaigns") {
            bearerAuth(token); parameter("limit",limit); cursor?.let { parameter("cursor",it) }
        }
        response.check(200)
        response.body<CampaignPage>().also { require(it.items.all { campaign -> campaign.eventId == eventId }) }
    }
    suspend fun create(eventId: Int,input: CampaignCreate): Campaign = requests.request { token ->
        val response=client.post("$baseUrl/organizer/events/$eventId/campaigns") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(input)
        }
        response.check(201)
        response.body<Campaign>().also { require(it.eventId == eventId && it.clientRequestId == input.clientRequestId) }
    }
}
