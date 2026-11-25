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
    val name: String, val code: String? = null,
    @SerialName("discount_type") val discountType: String,
    @SerialName("discount_value") val discountValue: Long,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("max_redemptions") val maxRedemptions: Long? = null,
    @SerialName("ticket_type_ids") val ticketTypeIds: List<Int> = emptyList(),
)
@Serializable
data class Campaign(
    val id:Int,@SerialName("event_id") val eventId:Int,val name:String,val code:String,
    @SerialName("discount_type") val discountType:String,@SerialName("discount_value") val discountValue:Long,
    @SerialName("starts_at") val startsAt:String?,@SerialName("ends_at") val endsAt:String?,
    @SerialName("max_redemptions") val maxRedemptions:Long?,
    @SerialName("redemptions_count") val redemptionsCount:Long,@SerialName("is_active") val isActive:Boolean,
    val link:String,@SerialName("link_opens") val linkOpens:Long,
    @SerialName("ticket_type_ids") val ticketTypeIds:List<Int>,@SerialName("created_at") val createdAt:String,val stats:CampaignStats,
)
@Serializable
data class CampaignStats(val orders:Long,val tickets:Long,val gross:Long,val discount:Long,val net:Long)
class CampaignApiException(val status: Int, val code: String?, val fields: Set<String>, override val message: String): Exception(message)

fun validateCampaign(input:CampaignCreate):Set<String> = buildSet {
    if(com.example.biletflow.presentation.support.supportTextLength(input.name) !in 1..200) add("name")
    if(input.code!=null && !Regex("[A-Za-z0-9-]{3,32}").matches(input.code)) add("code")
    if(input.discountType !in listOf("percent","fixed")) add("discount_type")
    if(input.discountValue<1 || input.discountType=="percent" && input.discountValue>100) add("discount_value")
    val start=input.startsAt?.let {runCatching {Instant.parse(it)}.getOrNull()}
    val end=input.endsAt?.let {runCatching {Instant.parse(it)}.getOrNull()}
    if(input.startsAt!=null && start==null) add("starts_at")
    if(input.endsAt!=null && end==null || start!=null && end!=null && end<=start) add("ends_at")
    if(input.maxRedemptions!=null && input.maxRedemptions<1) add("max_redemptions")
    if(input.ticketTypeIds.size>100) add("ticket_type_ids")
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
    suspend fun list(eventId:Int):List<Campaign> = requests.request {token ->
        val response=client.get("$baseUrl/events/$eventId/campaigns") {bearerAuth(token)}
        response.check(200)
        response.body<List<Campaign>>().also {require(it.all {campaign -> campaign.eventId==eventId})}
    }
    suspend fun create(eventId: Int,input: CampaignCreate): Campaign = requests.request { token ->
        val response=client.post("$baseUrl/events/$eventId/campaigns") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(input)
        }
        response.check(201)
        response.body<Campaign>().also { require(it.eventId == eventId) }
    }
    suspend fun get(campaignId:Int):Campaign = requests.request {token ->
        val response=client.get("$baseUrl/campaigns/$campaignId") {bearerAuth(token)}
        response.check(200)
        response.body<Campaign>().also {require(it.id==campaignId)}
    }
    /** Only name, starts_at, ends_at, max_redemptions, is_active; JsonNull clears a date or the limit. */
    suspend fun update(campaignId:Int,patch:JsonObject):Campaign = requests.request {token ->
        require(patch.keys.all {it in campaignPatchKeys})
        val response=client.patch("$baseUrl/campaigns/$campaignId") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(patch)
        }
        response.check(200)
        response.body<Campaign>().also {require(it.id==campaignId)}
    }
}
val campaignPatchKeys=setOf("name","starts_at","ends_at","max_redemptions","is_active")

/** Builds a PATCH body with only changed fields. Blank optional values become explicit null. */
fun campaignPatch(original:Campaign,values:Map<String,String>):Pair<JsonObject,Set<String>> {
    val errors=mutableSetOf<String>()
    val out=LinkedHashMap<String,JsonElement>()
    val name=values["name"]?.trim()
    if(name!=null && name!=original.name) { if(com.example.biletflow.presentation.support.supportTextLength(name) !in 1..200) errors+="name" else out["name"]=JsonPrimitive(name) }
    fun date(key:String,old:String?):Instant? {
        val raw=values[key]?.trim() ?: return old?.let {runCatching {Instant.parse(it)}.getOrNull()}
        if(raw.isEmpty()) { if(old!=null) out[key]=JsonNull; return null }
        val parsed=runCatching {Instant.parse(raw)}.getOrNull()
        if(parsed==null) { errors+=key; return null }
        if(old==null || runCatching {Instant.parse(old)}.getOrNull()!=parsed) out[key]=JsonPrimitive(raw)
        return parsed
    }
    val start=date("starts_at",original.startsAt); val end=date("ends_at",original.endsAt)
    if(start!=null && end!=null && end<=start) errors+="ends_at"
    values["max_redemptions"]?.trim()?.let { raw ->
        if(raw.isEmpty()) { if(original.maxRedemptions!=null) out["max_redemptions"]=JsonNull }
        else { val limit=raw.toLongOrNull()
            if(limit==null || limit<1 || limit<original.redemptionsCount) errors+="max_redemptions"
            else if(limit!=original.maxRedemptions) out["max_redemptions"]=JsonPrimitive(limit) }
    }
    values["is_active"]?.toBooleanStrictOrNull()?.let { if(it!=original.isActive) out["is_active"]=JsonPrimitive(it) }
    return JsonObject(out) to errors
}

/** After an unknown POST outcome: a user code identifies the campaign; a generated code needs a new id with the same name and discount. */
fun findCreatedCampaign(after:List<Campaign>,beforeIds:Set<Int>,input:CampaignCreate):Campaign? =
    if(input.code!=null) after.firstOrNull {it.code.equals(input.code,ignoreCase=true)}
    else after.filter {it.id !in beforeIds && it.name==input.name && it.discountType==input.discountType && it.discountValue==input.discountValue}.singleOrNull()

@Serializable
data class CampaignLink(@SerialName("event_id") val eventId: Int, @SerialName("promo_code") val promoCode: String? = null,
    @SerialName("discount_type") val discountType: String? = null, @SerialName("discount_value") val discountValue: Long? = null)

/** The opaque token of a Campaign QR link (`...?campaign=C-...`), or null when the text is not such a URL. */
fun campaignTokenFromLink(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    if (url.host.isEmpty()) return null
    return url.parameters["campaign"]?.trim()?.takeIf { it.isNotEmpty() }
}

/** Public GET /campaign-links/{token}. It counts an open, so call it once per transition. */
suspend fun resolveCampaignLink(client: HttpClient, baseUrl: String, token: String): CampaignLink {
    val response = client.get("$baseUrl/campaign-links/${token.encodeURLPathPart()}")
    if (response.status.value != 200) {
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        throw CampaignApiException(response.status.value, (body?.get("error") as? JsonPrimitive)?.contentOrNull, emptySet(), "HTTP ${response.status.value}")
    }
    return response.body()
}
