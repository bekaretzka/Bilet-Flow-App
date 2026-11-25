package com.example.biletflow.data.repositories

import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.domain.models.CurrentUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

val attendeeCaseCategories = listOf("ticket_delivery","payment","refund","seating","event_information","check_in","account","technical")
/** backend schemas/support.py SupportStatus: PATCH /status accepts any of these, from any current status. */
val supportStatuses = listOf("open","in_progress","waiting_for_customer","resolved")
@Serializable
data class SupportCaseCreate(val kind:String="attendee",val category:String,val subject:String,val message:String,
    @SerialName("event_id") val eventId:Int?=null,@SerialName("order_id") val orderId:Int?=null,@SerialName("ticket_id") val ticketId:Int?=null)
@Serializable
data class SupportCaseMessage(val id:Int,@SerialName("author_user_id") val authorUserId:Int?,val body:String,@SerialName("created_at") val createdAt:String)
@Serializable
data class SupportCase(val id:Int,val kind:String,val category:String,val subject:String,val status:String,
    @SerialName("requester_user_id") val requesterUserId:Int,
    @SerialName("event_id") val eventId:Int?,@SerialName("order_id") val orderId:Int?,@SerialName("ticket_id") val ticketId:Int?,
    @SerialName("assigned_user_id") val assignedUserId:Int?,
    @SerialName("created_at") val createdAt:String,@SerialName("updated_at") val updatedAt:String,
    val messages:List<SupportCaseMessage> = emptyList(),
    /** GET /support/cases/{id} only: status changes and assignments, oldest first. */
    val history:List<SupportHistoryEntry> = emptyList())
@Serializable
data class SupportHistoryEntry(@SerialName("created_at") val createdAt:String,val action:String,
    @SerialName("actor_user_id") val actorUserId:Int?=null,@SerialName("from_status") val fromStatus:String?=null,
    @SerialName("to_status") val toStatus:String?=null,@SerialName("assigned_user_id") val assignedUserId:Int?=null)
/** PATCH /status result; [includesMessages] is false when the body had no messages array, so the caller re-reads the case. */
data class AdminCandidate(val id:Int,val email:String?)
data class SupportStatusResult(val case:SupportCase,val includesMessages:Boolean)

class SupportApiException(val statusCode:Int,val code:String?,val fields:Set<String>,override val message:String):Exception(message)

/** Parses both the current {"error","message","fields"} body and the older FastAPI {"detail": ...} body. */
fun parseSupportError(status:Int,text:String):SupportApiException {
    val body=runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    val detail=body?.get("detail")
    val code=(body?.get("error") as? JsonPrimitive)?.contentOrNull
        ?: (detail as? JsonPrimitive)?.contentOrNull?.takeIf { it.endsWith("Error") }
        ?: if(detail is JsonArray) "VALIDATION_ERROR" else null
    val fields=(body?.get("fields") as? JsonObject)?.keys.orEmpty()+
        (detail as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("loc")?.jsonArray?.lastOrNull()?.jsonPrimitive?.contentOrNull }
    val message=((body?.get("message") ?: detail) as? JsonPrimitive)?.contentOrNull ?: (body?.get("message") ?: detail)?.toString() ?: "HTTP $status"
    return SupportApiException(status,code,fields,message)
}

class SupportCasesRepository(private val client:HttpClient,private val requests:SessionOrdersRepository,private val baseUrl:String=apiBaseUrl) {
    suspend fun create(payload:SupportCaseCreate):SupportCase = requests.request { token ->
        // Build explicitly: exactly one context is sent, with no null links or idempotency key.
        val body=buildJsonObject {
            put("kind",payload.kind);put("category",payload.category);put("subject",payload.subject);put("message",payload.message)
            payload.eventId?.let { put("event_id",it) };payload.orderId?.let { put("order_id",it) };payload.ticketId?.let { put("ticket_id",it) }
        }
        val response=client.post("$baseUrl/support/cases") {bearerAuth(token);contentType(ContentType.Application.Json);setBody(body)}
        response.checkStatus(201)
        response.body<SupportCase>().also {
            require(it.kind==payload.kind && (payload.eventId==null || it.eventId==payload.eventId) &&
                (payload.orderId==null || it.orderId==payload.orderId) && (payload.ticketId==null || it.ticketId==payload.ticketId))
        }
    }
    suspend fun mine():List<SupportCase> = read("/support/cases/me")
    suspend fun inbox(kind:String?=null,status:String?=null):List<SupportCase> = requests.request { token ->
        val response=client.get("$baseUrl/support/cases/inbox") {bearerAuth(token);kind?.let {parameter("kind",it)};status?.let {parameter("status",it)}}
        response.checkStatus(200);response.body<List<SupportCase>>()
    }
    /** PATCH /support/cases/{id}/assignee {"user_id": id or null}; null unassigns. Never retried automatically. */
    suspend fun assign(id:Int,userId:Int?):SupportStatusResult = requests.request { token ->
        val response=client.patch("$baseUrl/support/cases/$id/assignee") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(buildJsonObject { put("user_id",userId) })
        }
        response.checkStatus(200)
        val json=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val case=supportJson.decodeFromJsonElement(SupportCase.serializer(),json)
        require(case.id==id)
        SupportStatusResult(case,json["messages"] is JsonArray)
    }
    /** Platform admins for the assignee picker, searched by email via GET /admin/users?q=; the endpoint has no
     *  is_platform_admin filter, so only matching rows with is_platform_admin=true are kept. */
    suspend fun searchAdmins(query:String):List<AdminCandidate> = requests.request { token ->
        val response=client.get("$baseUrl/admin/users") {bearerAuth(token);parameter("q",query.trim());parameter("limit",50)}
        response.checkStatus(200)
        val items=Json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]?.jsonArray.orEmpty()
        items.mapNotNull { it as? JsonObject }.filter { (it["is_platform_admin"] as? JsonPrimitive)?.booleanOrNull==true }.mapNotNull { row ->
            val id=(row["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            AdminCandidate(id,(row["email"] as? JsonPrimitive)?.contentOrNull)
        }
    }
    suspend fun eventInbox(eventId:Int):List<SupportCase> = inbox().filter { it.eventId==eventId }
    suspend fun get(id:Int):SupportCase = read<SupportCase>("/support/cases/$id").also { require(it.id==id) }
    suspend fun me():CurrentUser = read("/auth/me")
    suspend fun ownsEvent(eventId:Int,organizerId:Int):Boolean {
        return try { read<com.example.biletflow.domain.models.Event>("/events/$eventId/manage").organizerId==organizerId }
        catch(error:Exception) { if(error is kotlinx.coroutines.CancellationException) throw error; false }
    }
    /** POST /support/cases/{id}/messages with only {"body"}; never retried automatically. */
    suspend fun send(id:Int,body:String):SupportCaseMessage = requests.request { token ->
        val response=client.post("$baseUrl/support/cases/$id/messages") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(buildJsonObject { put("body",body) })
        }
        response.checkStatus(201);response.body<SupportCaseMessage>()
    }
    /** PATCH /support/cases/{id}/status with only {"status"}. */
    suspend fun changeStatus(id:Int,status:String):SupportStatusResult = requests.request { token ->
        val response=client.patch("$baseUrl/support/cases/$id/status") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(buildJsonObject { put("status",status) })
        }
        response.checkStatus(200)
        val json=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val case=supportJson.decodeFromJsonElement(SupportCase.serializer(),json)
        require(case.id==id)
        SupportStatusResult(case,json["messages"] is JsonArray)
    }
    private suspend inline fun <reified T> read(path:String):T = requests.request { token ->
        val response=client.get("$baseUrl$path") {bearerAuth(token)}
        response.checkStatus(200);response.body<T>()
    }
    private suspend fun HttpResponse.checkStatus(expected:Int) {
        if(status.value!=expected) throw parseSupportError(status.value,bodyAsText())
    }
}
private val supportJson=Json { ignoreUnknownKeys=true }
