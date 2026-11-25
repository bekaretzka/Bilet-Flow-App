package com.example.biletflow.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.io.IOException
import kotlinx.datetime.Instant
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.biletflow.data.repositories.CampaignCreate
import com.example.biletflow.data.repositories.validateCampaign

// The app enables a ready-to-buy concert; activation tests keep the incomplete checklist fixtures.
fun createMockEventsHttpClient(visitorCheckoutReady: Boolean = false, catalogPageFixtures: Boolean = false, publishedFixtureEvents: Boolean = false, seedPublishedEventIds: Set<Int> = emptySet(), seedSuspendedEventIds: Set<Int> = emptySet()): HttpClient {
    val catalogExtra = if(catalogPageFixtures) (1..25).map { n -> buildJsonObject {
        mockEvents.first().jsonObject.forEach { (key,value) -> put(key,value) }
        put("id",10000+n); put("title",if(n==25) "Дальний спектакль" else "Каталог $n")
        put("category",if(n==25) "Театр" else "Концерт")
        put("description","Программа за пределами первой страницы")
        put("start_time","2027-04-01T10:00:00+05:00"); put("end_time","2027-04-01T12:00:00+05:00")
        put("image_url",JsonNull); put("lifecycle","upcoming"); put("suspended_at",JsonNull); put("suspension_reason",JsonNull)
    } } else emptyList()
    val organizerProfiles = mutableMapOf<String, String>()
    val createdTicketTypes = mutableMapOf<String, MutableList<JsonObject>>()
    var nextTicketTypeId = 3000
    // One event store for every route. Seed status is written once, never synthesized on read.
    val createdEvents = mutableMapOf<String, MutableList<JsonObject>>()
    val seeded = mockEvents.map { it.jsonObject }.map { base -> buildJsonObject {
        base.forEach { (k,v) -> put(k,v) }
        put("organizer_id",if(base.getValue("id").jsonPrimitive.int in 101..103) 31 else 99)
        if(base.getValue("id").jsonPrimitive.int in seedSuspendedEventIds) put("suspended_at","2026-11-24T00:00:00Z")
        when(base.getValue("id").jsonPrimitive.int) {
            101 -> {put("status",if(publishedFixtureEvents) "published" else "draft");put("visibility","public")}
            103 -> {put("status",if(103 in seedPublishedEventIds) "published" else "cancelled");put("visibility",if(103 in seedPublishedEventIds) "public" else "unlisted")}
        }
    } }
    createdEvents["Bearer mock-access-token"] = seeded.take(3).toMutableList()
    createdEvents["catalog-seed"] = (seeded.drop(3)+catalogExtra.map { row -> buildJsonObject {row.forEach { (k,v)->put(k,v) };put("organizer_id",99)} }).toMutableList()
    fun allEvents() = createdEvents.values.flatten()
    fun eventById(id:Int) = allEvents().firstOrNull {it["id"]?.jsonPrimitive?.int==id}
    fun publicPage(event:JsonObject?) = event!=null && event["status"]?.jsonPrimitive?.content=="published" &&
        event["visibility"]?.jsonPrimitive?.content!="private" && (event["suspended_at"]==null || event["suspended_at"]==JsonNull)
    fun catalogPublic() = allEvents().filter {publicPage(it) && it["visibility"]?.jsonPrimitive?.content=="public"}
    val now = Instant.parse("2026-11-25T00:00:00Z")
    fun eventTypes(id:Int):List<JsonObject> {
        val base=when(id) {101->MOCK_EVENT_101_TICKET_TYPES_JSON;102->MOCK_EVENT_102_TICKET_TYPES_JSON;else->"[]"}
        val changed=createdTicketTypes.values.flatten().filter {it["event_id"]?.jsonPrimitive?.int==id}
        return (changed+Json.parseToJsonElement(base).jsonArray.map {it.jsonObject}).distinctBy {it["id"]}
    }
    val reservedOrders=mutableSetOf(7101,7102,7103,7110)
    fun changeInventory(order:JsonObject,reserve:Boolean) {
        val id=order.getValue("id").jsonPrimitive.int
        if(reserve) {if(!reservedOrders.add(id)) return} else if(!reservedOrders.remove(id)) return
        val eventId=order.getValue("event_id").jsonPrimitive.int
        val owner=createdEvents.entries.firstOrNull {(_,rows)->rows.any {it["id"]?.jsonPrimitive?.int==eventId}}?.key ?: return
        val types=eventTypes(eventId).associateBy {it.getValue("id").jsonPrimitive.int}
        val changed=createdTicketTypes.getOrPut(owner) {mutableListOf()}
        order["items"]?.jsonArray.orEmpty().forEach {item ->
            val typeId=item.jsonObject.getValue("ticket_type_id").jsonPrimitive.int
            val type=types[typeId] ?: return@forEach
            val count=item.jsonObject.getValue("quantity").jsonPrimitive.int*(if(reserve) 1 else -1)
            val updated=buildJsonObject {type.forEach {(k,v)->put(k,v)};put("sold_count",maxOf(0,type.getValue("sold_count").jsonPrimitive.int+count))}
            changed.removeAll {it["id"]?.jsonPrimitive?.int==typeId};changed.add(updated)
        }
    }
    var nextEventId = 500
    val organizerReads = mutableMapOf<String, Int>()
    val myEventReads = mutableMapOf<String, Int>()
    val paidSalesReads = mutableMapOf<Int, Int>()
    val consumedRefreshTokens = mutableSetOf<String>()
    val accessAliases = mutableMapOf<String, String>()
    val refreshOwners = mutableMapOf(
        "mock-refresh-token" to "Bearer mock-access-token",
        "mock-attendee-refresh-token" to "Bearer mock-attendee-access-token",
        "mock-empty-refresh-token" to "Bearer mock-empty-access-token",
        "mock-history-offline-refresh-token" to "Bearer mock-history-offline-token",
    )
    var rotation = 0
    val registeredUsers = mutableMapOf<String, String>()
    val suspendedOwners = mutableSetOf<String>()
    val meReads = mutableMapOf<String,Int>()
    fun isBuyer(owner: String?): Boolean = owner in refreshOwners.values ||
        owner?.removePrefix("Bearer mock-user-")?.toIntOrNull()?.let { it in registeredUsers.values.indices } == true
    fun buyerEmail(owner: String?): String = when (owner) {
        "Bearer mock-attendee-access-token" -> "attendee@biletflow.kz"
        "Bearer mock-access-token" -> "visitor@biletflow.kz"
        "Bearer mock-empty-access-token" -> "empty@biletflow.kz"
        "Bearer mock-history-offline-token" -> "history-offline@biletflow.kz"
        else -> registeredUsers.keys.elementAt(owner!!.removePrefix("Bearer mock-user-").toInt())
    }
    val orderOwners = mutableMapOf(7101 to "Bearer mock-access-token", 7102 to "Bearer mock-access-token", 7103 to "Bearer mock-access-token")
    val supportCases = mutableMapOf<Int, JsonObject>()
    val supportCaseOwners = mutableMapOf<Int, String>()
    val supportCaseReads = mutableMapOf<String, Int>()
    var nextSupportCaseId = 20000
    val supportMutex = Mutex()
    val campaignMutex = Mutex()
    var nextCampaignId = 12000
    val campaigns = mutableMapOf<Int,JsonObject>()
    var event104TicketAttempts = 0
    val mockOrders = mutableMapOf<Int, String>()
    val paymentAttempts = mutableMapOf<Int, Int>()
    val cancellationAttempts = mutableMapOf<Int, Int>()
    val refundAttempts = mutableMapOf<Int, Int>()
    val qrAttempts = mutableMapOf<Int, Int>()
    val acceptedTerms = mutableSetOf<Int>()
    val paidFees = mutableSetOf<Int>()
    val activeSales = mutableSetOf<Int>()
    val feeAttempts = mutableMapOf<Int, Int>()
    val attendeeReads = mutableMapOf<Int, Int>()
    val eventStaff = mutableMapOf<Int, MutableMap<Int, JsonObject>>()
    val staffReads = mutableMapOf<Int, Int>()
    val unknownStaffAssignments = mutableSetOf<Int>()
    val unknownStaffRemovals = mutableSetOf<Int>()
    val scannerReads = mutableMapOf<String, Int>()
    val salesReads = mutableMapOf<Int, Int>()
    val checkInReads = mutableMapOf<String, Int>()
    val lostCheckInResponses = mutableSetOf<Int>()
    val lostUndoResponses = mutableSetOf<Int>()
    fun scanTicket(ticket: JsonObject): JsonObject {
        val types = (Json.parseToJsonElement(MOCK_EVENT_101_TICKET_TYPES_JSON).jsonArray + Json.parseToJsonElement(MOCK_EVENT_102_TICKET_TYPES_JSON).jsonArray)
            .map { it.jsonObject } + createdTicketTypes.values.flatten()
        val type = types.lastOrNull { it["id"] == ticket["ticket_type_id"] }
        return buildJsonObject {
            listOf("id", "code", "attendee_name", "status", "checked_in_at").forEach { put(it, ticket[it] ?: JsonNull) }
            put("ticket_type_name", type?.get("name") ?: JsonPrimitive("Ticket"))
        }
    }
    fun ownedEvents(owner: String): List<JsonObject> = if(organizerProfiles[owner]==null) emptyList() else createdEvents[owner].orEmpty()
    fun ownsSupportEvent(owner: String, eventId: Int): Boolean = organizerProfiles.containsKey(owner) && ownedEvents(owner).any { it["id"]?.jsonPrimitive?.int == eventId }
    var nextSupportMessageId = 100000
    var supportClock = 0
    /** Monotonic mock timestamps for support messages and case updates. */
    fun supportNow(): String { val s = supportClock++; return "2026-11-25T13:${(s / 60 % 60).toString().padStart(2,'0')}:${(s % 60).toString().padStart(2,'0')}+05:00" }
    fun mockUserId(owner: String): Int = when(owner) { "Bearer mock-attendee-access-token" -> 103; "Bearer mock-access-token" -> 100; "Bearer mock-empty-access-token" -> 101; "Bearer mock-history-offline-token" -> 102; else -> 1000 + owner.removePrefix("Bearer mock-user-").toInt() }
    /** backend support_service._is_handler: platform admin, or the event owner for attendee cases. */
    fun supportHandles(owner: String, item: JsonObject): Boolean = isBuyer(owner) && buyerEmail(owner) == "admin@biletflow.kz" ||
        item["kind"]?.jsonPrimitive?.content == "attendee" && ownsSupportEvent(owner, item["event_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1)
    fun supportValidation(field: String) = buildJsonObject { put("error","VALIDATION_ERROR"); put("message","Invalid data"); put("fields",buildJsonObject { put(field,"Invalid value") }) }.toString()

    var nextCreatedOrderId = 7200
    fun paidStatus(owner: String?, id: Int): JsonObject {
        val event = createdEvents[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
        val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
        val suspended = event?.get("title")?.jsonPrimitive?.content == "paid-sales-suspended"
        val visitorFixture = visitorCheckoutReady && id == 101
        val active = visitorFixture || id == 103 || id in activeSales
        val fixtureComplete = visitorFixture || id == 103 || suspended
        val status = when {
            suspended -> "suspended"
            active -> "active"
            id == 102 || id in acceptedTerms || id in paidFees -> "pending"
            else -> "not_started"
        }
        val checklist = buildJsonObject {
            put("has_paid_ticket_type", fixtureComplete || id in 101..102 || createdTicketTypes[owner].orEmpty().any {
                it["event_id"]?.jsonPrimitive?.int == id && (it["price"]?.jsonPrimitive?.int ?: 0) > 0
            })
            put("identity_verified", fixtureComplete || id == 102 || profile?.get("identity_verified_at")?.let { it != JsonNull } == true)
            put("payout_account_connected", fixtureComplete || profile?.get("payout_account")?.let { it != JsonNull } == true)
            put("terms_accepted", fixtureComplete || id in acceptedTerms)
            put("fee_paid", fixtureComplete || id in paidFees)
        }
        return buildJsonObject {
            put("event_id", id); put("status", status)
            put("fee_amount", 5000); put("currency", "KZT"); put("is_simulated", true)
            put("checklist", checklist)
            put("can_activate", status in listOf("not_started", "pending") && checklist.values.all { it.jsonPrimitive.content == "true" })
            put("activated_at", if (active || suspended) JsonPrimitive("2026-11-23T10:00:00+05:00") else JsonNull)
            put("suspended_at", if (suspended) JsonPrimitive("2026-11-23T11:00:00+05:00") else JsonNull)
            put("suspension_reason", if (suspended) JsonPrimitive("Mock review required") else JsonNull)
        }
    }
    mockOrders[7101] = mockOrderResponse(
        orderId = 7101,
        eventId = 101,
        buyerName = "Mock Visitor",
        items = listOf(1001 to 1),
        createdAt = "2026-11-20T18:00:00+05:00",
    )
    mockOrders[7102] = mockOrderResponse(
        orderId = 7102,
        eventId = 101,
        buyerName = "Mock Visitor",
        items = listOf(1002 to 1),
        createdAt = "2026-11-20T12:00:00+05:00",
    )
    mockOrders[7103] = mockOrderResponse(
        orderId = 7103,
        eventId = 102,
        buyerName = "Cancel Unknown",
        items = listOf(2002 to 1),
        createdAt = "2026-11-13T10:00:00+05:00",
    )
    // Historical 101 orders were created while it was published, then the event was unpublished.
    mockOrders[7110]=mockOrderResponse(7110,102,"Seed attendee",listOf(2001 to 1),createdAt="2026-11-20T10:00:00+05:00",buyerEmail="attendee@biletflow.kz")
    orderOwners[7110]="Bearer mock-attendee-access-token"
    return HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val rawAuthorization = request.headers[HttpHeaders.Authorization]
        val authorizationHeader = accessAliases[rawAuthorization] ?: rawAuthorization
        if(isBuyer(authorizationHeader)) {
            val email=buyerEmail(authorizationHeader)
            if(path == "/auth/me" && email == "suspend-on-me@biletflow.kz" ||
                path == "/orders/me" && email == "suspend-on-orders@biletflow.kz" ||
                path.endsWith("/ticket-types/manage") && email == "suspend-on-manage@biletflow.kz" ||
                path.matches(Regex("/events/\\d+/orders")) && email == "suspend-on-event-orders@biletflow.kz" ||
                path.startsWith("/support/cases") && email == "suspend-on-cases@biletflow.kz" ||
                path.endsWith("/campaigns") && email=="suspend-on-campaigns@biletflow.kz") suspendedOwners.add(authorizationHeader.orEmpty())
            if(authorizationHeader in suspendedOwners && (!path.startsWith("/auth/") || path == "/auth/me"))
                return@MockEngine respondJson("""{"error":"UserSuspendedError","message":"Account suspended","fields":null}""",HttpStatusCode.Forbidden)
        }
        when {
            request.method == HttpMethod.Post && path == "/support/cases" -> supportMutex.withLock {
                val owner=authorizationHeader.orEmpty()
                val input=Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                fun number(key:String)=input[key]?.jsonPrimitive?.content?.toIntOrNull()
                val eventInput=number("event_id"); val orderInput=number("order_id"); val ticketInput=number("ticket_id")
                val kind=input["kind"]?.jsonPrimitive?.content.orEmpty()
                val category=input["category"]?.jsonPrimitive?.content.orEmpty()
                val subject=input["subject"]?.jsonPrimitive?.content.orEmpty().trim()
                val message=input["message"]?.jsonPrimitive?.content.orEmpty().trim()
                val given=listOfNotNull(eventInput,orderInput,ticketInput).size
                val errors=buildSet {
                    if(kind !in listOf("attendee","platform")) add("kind")
                    if(kind=="attendee" && given!=1 || kind=="platform" && (orderInput!=null || ticketInput!=null)) add("context")
                    if(category !in (if(kind=="platform") listOf("account","activation","payment","technical") else com.example.biletflow.data.repositories.attendeeCaseCategories)) add("category")
                    if(com.example.biletflow.presentation.support.supportTextLength(subject) !in 1..200) add("subject")
                    if(com.example.biletflow.presentation.support.supportTextLength(message) !in 1..5000) add("message")
                }
                val ticketOrder=mockOrders.entries.firstOrNull { (_,json) -> Json.parseToJsonElement(json).jsonObject["tickets"]?.jsonArray.orEmpty().any {it.jsonObject["id"]?.jsonPrimitive?.int==ticketInput} }
                val orderId=orderInput ?: ticketOrder?.key
                val order=mockOrders[orderId]?.let {Json.parseToJsonElement(it).jsonObject}
                val eventId=eventInput ?: order?.get("event_id")?.jsonPrimitive?.int
                val event=createdEvents.values.flatten().firstOrNull {it["id"]?.jsonPrimitive?.int==eventId}
                    ?: eventById(eventId ?: -1)
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    errors.isNotEmpty() -> respondJson(buildJsonObject {
                        put("error","VALIDATION_ERROR");put("message","Invalid support case");put("fields",buildJsonObject {errors.forEach {put(it,"Invalid value")}})
                    }.toString(),HttpStatusCode.UnprocessableEntity)
                    ticketInput!=null && (ticketOrder==null || orderOwners[ticketOrder.key]!=owner) -> respondJson("""{"error":"TicketNotFoundError","message":"Ticket not found","fields":null}""",HttpStatusCode.NotFound)
                    orderInput!=null && (order==null || orderOwners[orderInput]!=owner) -> respondJson(MOCK_ORDER_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    kind=="platform" && organizerProfiles[owner]==null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""",HttpStatusCode.NotFound)
                    kind=="platform" && eventInput!=null && !ownsSupportEvent(owner,eventInput) -> respondJson("""{"error":"EventAccessDeniedError","message":"Not event owner","fields":null}""",HttpStatusCode.Forbidden)
                    kind=="attendee" && eventInput!=null && (event==null || event["status"]?.jsonPrimitive?.content!="published" || event["visibility"]?.jsonPrimitive?.content=="private") -> respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    else -> {
                        val id=nextSupportCaseId++
                        val userId=when(owner) { "Bearer mock-attendee-access-token" -> 103;"Bearer mock-access-token"->100;"Bearer mock-empty-access-token"->101;"Bearer mock-history-offline-token"->102;else->1000+owner.removePrefix("Bearer mock-user-").toInt()}
                        val result=buildJsonObject {
                            put("id",id);put("kind",kind);put("category",category);put("subject",subject);put("status","open");put("requester_user_id",userId)
                            put("event_id",eventId?.let(::JsonPrimitive) ?: JsonNull);put("order_id",orderId?.let(::JsonPrimitive) ?: JsonNull)
                            put("ticket_id",ticketInput?.let(::JsonPrimitive) ?: JsonNull);put("assigned_user_id",JsonNull)
                            put("created_at","2026-11-25T12:00:00+05:00");put("updated_at","2026-11-25T12:00:00+05:00")
                            put("messages",buildJsonArray {add(buildJsonObject {put("id",nextSupportMessageId++);put("author_user_id",userId);put("body",message);put("created_at","2026-11-25T12:00:00+05:00")})})
                        }
                        supportCases[id]=result;supportCaseOwners[id]=owner
                        if(subject=="support-case-unknown") throw IOException("Case saved; response lost")
                        respondJson(result.toString(),HttpStatusCode.Created)
                    }
                }
            }
            request.method == HttpMethod.Get && (path=="/support/cases/me" || path=="/support/cases/inbox" || path.matches(Regex("/support/cases/\\d+"))) -> {
                val owner=authorizationHeader.orEmpty()
                val admin=isBuyer(owner) && buyerEmail(owner)=="admin@biletflow.kz"
                val approved=organizerProfiles[owner]?.let {Json.parseToJsonElement(it).jsonObject["status"]?.jsonPrimitive?.content=="approved"} == true
                fun handles(item:JsonObject)=admin || item["kind"]?.jsonPrimitive?.content=="attendee" && ownsSupportEvent(owner,item["event_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1)
                val id=path.substringAfterLast('/').toIntOrNull()
                val item=supportCases[id]
                val reads=supportCaseReads.getOrElse(owner+path) {0};supportCaseReads[owner+path]=reads+1
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    path=="/support/cases/inbox" && !admin && !approved -> respondJson("""{"error":"SupportCaseAccessDeniedError","message":"Inbox access denied","fields":null}""",HttpStatusCode.Forbidden)
                    id!=null && (item==null || supportCaseOwners[id]!=owner && !handles(item)) -> respondJson("""{"error":"SupportCaseNotFoundError","message":"Case not found","fields":null}""",HttpStatusCode.NotFound)
                    buyerEmail(owner)=="cases-offline@biletflow.kz" && reads==0 -> throw IOException("Mock support list unavailable")
                    id!=null -> respondJson(requireNotNull(item).toString(),HttpStatusCode.OK)
                    else -> {
                        val items=supportCases.values.filter {
                            (if(path.endsWith("/me")) supportCaseOwners[it["id"]?.jsonPrimitive?.int]==owner else handles(it)) &&
                                (request.url.parameters["kind"]==null || request.url.parameters["kind"]==it["kind"]?.jsonPrimitive?.content) &&
                                (request.url.parameters["status"]==null || request.url.parameters["status"]==it["status"]?.jsonPrimitive?.content)
                        }.sortedByDescending {it["id"]?.jsonPrimitive?.int}
                        respondJson(buildJsonArray {items.forEach {row -> add(buildJsonObject {row.filterKeys {it!="messages"}.forEach { (k,v)->put(k,v) }})}}.toString(),HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/support/cases/\\d+/messages")) -> supportMutex.withLock {
                val owner=authorizationHeader.orEmpty()
                val id=path.split('/')[3].toInt();val item=supportCases[id]
                val input=runCatching {Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject}.getOrNull()
                val body=(input?.get("body") as? JsonPrimitive)?.takeIf {it.isString}?.content?.let {com.example.biletflow.presentation.support.supportTrim(it)}
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    // FastAPI validates the body before the service runs, so 422 comes before 404
                    body==null || com.example.biletflow.presentation.support.supportTextLength(body) !in 1..5000 -> respondJson(supportValidation("body"),HttpStatusCode.UnprocessableEntity)
                    item==null || supportCaseOwners[id]!=owner && !supportHandles(owner,item) -> respondJson("""{"error":"SupportCaseNotFoundError","message":"Support Case Not Found","fields":null}""",HttpStatusCode.NotFound)
                    else -> {
                        val now=supportNow();val author=mockUserId(owner)
                        val message=buildJsonObject {put("id",nextSupportMessageId++);put("author_user_id",author);put("body",body);put("created_at",now)}
                        val status=item.getValue("status").jsonPrimitive.content
                        // backend: only the requester's reply reopens waiting_for_customer or resolved; a handler's reply changes nothing
                        val reopened=if(supportCaseOwners[id]==owner && status in listOf("waiting_for_customer","resolved")) "open" else status
                        supportCases[id]=buildJsonObject {
                            item.forEach {(key,value) -> if(key!="messages" && key!="status" && key!="updated_at") put(key,value)}
                            put("status",reopened);put("updated_at",now)
                            put("messages",buildJsonArray {item["messages"]?.jsonArray?.forEach {add(it)};add(message)})
                        }
                        respondJson(message.toString(),HttpStatusCode.Created)
                    }
                }
            }
            request.method == HttpMethod.Patch && path.matches(Regex("/support/cases/\\d+/status")) -> supportMutex.withLock {
                val owner=authorizationHeader.orEmpty()
                val id=path.split('/')[3].toInt();val item=supportCases[id]
                val input=runCatching {Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject}.getOrNull()
                val target=(input?.get("status") as? JsonPrimitive)?.takeIf {it.isString}?.content
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    target !in com.example.biletflow.data.repositories.supportStatuses -> respondJson(supportValidation("status"),HttpStatusCode.UnprocessableEntity)
                    item==null || supportCaseOwners[id]!=owner && !supportHandles(owner,item) -> respondJson("""{"error":"SupportCaseNotFoundError","message":"Support Case Not Found","fields":null}""",HttpStatusCode.NotFound)
                    !supportHandles(owner,item) -> respondJson("""{"error":"SupportCaseAccessDeniedError","message":"Only The Case Handlers Can Do This","fields":null}""",HttpStatusCode.Forbidden)
                    item.getValue("status").jsonPrimitive.content==target -> respondJson(item.toString(),HttpStatusCode.OK)
                    else -> {
                        // backend allows any of the four statuses from any status
                        val updated=buildJsonObject {
                            item.forEach {(key,value) -> if(key!="status" && key!="updated_at") put(key,value)}
                            put("status",requireNotNull(target));put("updated_at",supportNow())
                        }
                        supportCases[id]=updated
                        respondJson(updated.toString(),HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Get && path == "/auth/me" -> {
                val owner=authorizationHeader.orEmpty()
                if(!isBuyer(owner)) respondJson("""{"error":"InvalidTokenError","message":"Invalid token","fields":null}""",HttpStatusCode.Unauthorized)
                else {
                    val email=buyerEmail(owner)
                    val reads=meReads.getOrElse(owner) { 0 }; meReads[owner]=reads+1
                    if(email == "me-offline@biletflow.kz" && reads == 0) throw IOException("Mock current user network unavailable")
                    else {
                        val userId=when(owner) { "Bearer mock-attendee-access-token" -> 103; "Bearer mock-access-token" -> 100; "Bearer mock-empty-access-token" -> 101; "Bearer mock-history-offline-token" -> 102; else -> 1000+owner.removePrefix("Bearer mock-user-").toInt() }
                        var profile=organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                        if(profile != null && profile["status"]?.jsonPrimitive?.content == "pending") {
                            val reloads=organizerReads.getOrElse(owner) { 0 }+1; organizerReads[owner]=reloads
                            if(reloads >= 3) {
                                profile=buildJsonObject { profile!!.forEach { (k,v) -> put(k,v) }; put("status","approved") }
                                organizerProfiles[owner]=profile.toString()
                            }
                        }
                        val allEvents=(organizerProfiles.keys+createdEvents.keys).distinct().flatMap { ownedEvents(it) }
                        val staff=eventStaff.any { (id,users) -> userId in users && allEvents.any { it["id"]?.jsonPrimitive?.int == id && it["status"]?.jsonPrimitive?.content != "draft" } }
                        respondJson(buildJsonObject {
                            put("id",userId); put("email",email); put("email_verified",email == "visitor@biletflow.kz")
                            put("is_platform_admin",email == "admin@biletflow.kz"); put("is_event_staff",staff)
                            put("organizer",profile?.let { original -> buildJsonObject { listOf("id","user_id","status").forEach { key -> put(key,original.getValue(key)) } } } ?: JsonNull)
                        }.toString(),HttpStatusCode.OK)
                    }
                }
            }
            path.matches(Regex("/events/\\d+/campaigns")) && request.method in listOf(HttpMethod.Get,HttpMethod.Post) -> campaignMutex.withLock {
                val owner=authorizationHeader.orEmpty();val eventId=path.split('/')[2].toInt()
                val profile=organizerProfiles[owner]?.let {Json.parseToJsonElement(it).jsonObject}
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    profile==null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found"}""",HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content!="approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Not approved"}""",HttpStatusCode.Forbidden)
                    eventById(eventId)==null -> respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    !ownsSupportEvent(owner,eventId) -> respondJson("""{"error":"EventAccessDeniedError","message":"Not owner"}""",HttpStatusCode.Forbidden)
                    request.method==HttpMethod.Get -> respondJson(JsonArray(campaigns.values.filter {it["event_id"]?.jsonPrimitive?.int==eventId}.sortedByDescending {it.getValue("id").jsonPrimitive.int}).toString(),HttpStatusCode.OK)
                    else -> {
                        val input=runCatching {Json.decodeFromString<CampaignCreate>((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())}.getOrNull()
                        val errors=input?.let(::validateCampaign) ?: setOf("body")
                        val types=eventTypes(eventId).associateBy {it.getValue("id").jsonPrimitive.int}
                        val code=input?.code?.uppercase() ?: buildString {
                            val alphabet="ABCDEFGHJKMNPQRSTUVWXYZ23456789";var value=nextCampaignId
                            repeat(8) {append(alphabet[value%alphabet.length]);value/=alphabet.length}
                        }
                        when {
                            eventById(eventId)?.get("status")?.jsonPrimitive?.content=="cancelled" -> respondJson("""{"error":"InvalidEventStatusError","message":"Event cancelled"}""",HttpStatusCode.Conflict)
                            input==null || errors.isNotEmpty() -> respondJson(buildJsonObject {put("error","VALIDATION_ERROR");put("message","Invalid campaign");put("fields",buildJsonObject {errors.forEach {put(it,"Invalid value")}})}.toString(),HttpStatusCode.UnprocessableEntity)
                            input.ticketTypeIds.any {id -> types[id]==null || types[id]?.get("price")?.jsonPrimitive?.int==0} -> respondJson("""{"error":"InvalidCampaignError","message":"Choose paid types of this event","fields":null}""",HttpStatusCode.BadRequest)
                            campaigns.values.any {it["event_id"]?.jsonPrimitive?.int==eventId && it["code"]?.jsonPrimitive?.content==code} -> respondJson("""{"error":"PromoCodeAlreadyExistsError","message":"Code already used at this event","fields":{"code":"Already used"}}""",HttpStatusCode.Conflict)
                            input.name=="campaign-offline" -> throw IOException("Mock campaign unavailable")
                            else -> {
                                val id=nextCampaignId++
                                val result=buildJsonObject {
                                    put("id",id);put("event_id",eventId);put("name",input.name);put("code",code)
                                    put("discount_type",input.discountType);put("discount_value",input.discountValue)
                                    put("starts_at",input.startsAt?.let(::JsonPrimitive) ?: JsonNull);put("ends_at",input.endsAt?.let(::JsonPrimitive) ?: JsonNull)
                                    put("max_redemptions",input.maxRedemptions?.let(::JsonPrimitive) ?: JsonNull)
                                    put("ticket_type_ids",JsonArray(input.ticketTypeIds.distinct().map(::JsonPrimitive)))
                                    put("redemptions_count",0);put("is_active",true);put("link","http://localhost:5173/events/$eventId?campaign=mock-token-$id");put("link_opens",0)
                                    put("created_at","2026-11-25T12:00:00+05:00")
                                    put("stats",buildJsonObject {listOf("orders","tickets","gross","discount","net").forEach {put(it,0)}})
                                }
                                campaigns[id]=result
                                if(input.name=="campaign-unknown") throw IOException("Campaign saved; response lost")
                                respondJson(result.toString(),HttpStatusCode.Created)
                            }
                        }
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/orders")) -> {
                val owner = authorizationHeader.orEmpty()
                val eventId = path.split('/')[2].toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val exists = allEvents().any { it["id"]?.jsonPrimitive?.int == eventId } ||
                    createdEvents.values.flatten().any { it["id"]?.jsonPrimitive?.int == eventId }
                val limit = request.url.parameters["limit"]?.toIntOrNull() ?: if(request.url.parameters["limit"] == null) 50 else 0
                val offset = request.url.parameters["offset"]?.toIntOrNull() ?: if(request.url.parameters["offset"] == null) 0 else -1
                val status = request.url.parameters["status"]
                val query = request.url.parameters["q"].orEmpty().trim()
                val all = mockOrders.values.map { Json.parseToJsonElement(it).jsonObject }
                    .filter { it["event_id"]?.jsonPrimitive?.int == eventId && (status == null || it["status"]?.jsonPrimitive?.content == status) &&
                        (query.isEmpty() || listOf("buyer_name","buyer_email").any { key -> it[key]?.jsonPrimitive?.content?.contains(query,true) == true }) }
                    .sortedByDescending { it.getValue("id").jsonPrimitive.int }
                fun projection(order: JsonObject) = buildJsonObject {
                    listOf("id","status","total_amount","buyer_name","buyer_email","created_at","paid_at","cancelled_at","refunded_at")
                        .forEach { put(it,order[it] ?: JsonNull) }
                    put("discount_amount",order["discount_amount"] ?: JsonPrimitive(0))
                    put("promo_code",order["promo_code"] ?: JsonNull)
                    put("processing_fee",order["processing_fee"] ?: JsonPrimitive((order.getValue("total_amount").jsonPrimitive.long * 3 + 50) / 100))
                    put("tickets",order["tickets"]?.jsonArray?.size ?: 0)
                    put("payment_reference", if(order["paid_at"] != JsonNull && order["paid_at"] != null && order["total_amount"]!!.jsonPrimitive.long > 0) JsonPrimitive("SIM-${order["id"]}") else JsonNull)
                    put("refund",order["refund"] ?: JsonNull)
                    put("is_simulated",true)
                }
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""",HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Not approved","fields":null}""",HttpStatusCode.Forbidden)
                    !exists -> respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    ownedEvents(owner).none { it["id"]?.jsonPrimitive?.int == eventId } -> respondJson("""{"error":"EventAccessDeniedError","message":"Not the event owner","fields":null}""",HttpStatusCode.Forbidden)
                    limit !in 1..200 || offset < 0 || status != null && status !in listOf("pending","confirmed","cancelled","refunded","expired") ->
                        respondJson("""{"error":"VALIDATION_ERROR","message":"Invalid pagination or status","fields":null}""",HttpStatusCode.UnprocessableEntity)
                    else -> respondJson(buildJsonObject {
                        put("items",buildJsonArray { all.drop(offset).take(limit).forEach { add(projection(it)) } })
                        put("total",all.size)
                    }.toString(),HttpStatusCode.OK)
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/ticket-types/manage")) -> {
                val owner = authorizationHeader.orEmpty()
                val eventId = path.split('/')[2].toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val exists = allEvents().any { it["id"]?.jsonPrimitive?.int == eventId } ||
                    createdEvents.values.flatten().any { it["id"]?.jsonPrimitive?.int == eventId }
                val stored = createdTicketTypes[owner].orEmpty().filter { it["event_id"]?.jsonPrimitive?.int == eventId }
                val seeded = if(owner == "Bearer mock-access-token") when(eventId) {
                    101 -> Json.parseToJsonElement(MOCK_EVENT_101_TICKET_TYPES_JSON).jsonArray.map { it.jsonObject }
                    102 -> Json.parseToJsonElement(MOCK_EVENT_102_TICKET_TYPES_JSON).jsonArray.map { it.jsonObject }
                    else -> emptyList()
                } else emptyList()
                val types = (stored + seeded).distinctBy { it["id"] }.sortedBy { it.getValue("id").jsonPrimitive.int }
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""", HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Not approved","fields":null}""", HttpStatusCode.Forbidden)
                    !exists -> respondJson(MOCK_EVENT_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                    ownedEvents(owner).none { it["id"]?.jsonPrimitive?.int == eventId } -> respondJson("""{"error":"EventAccessDeniedError","message":"Not the event owner","fields":null}""", HttpStatusCode.Forbidden)
                    else -> respondJson(buildJsonArray { types.forEach { add(it) } }.toString(), HttpStatusCode.OK)
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/orders/\\d+/refund")) -> {
                val owner = authorizationHeader.orEmpty()
                val orderId = path.removePrefix("/orders/").substringBefore('/').toInt()
                val order = mockOrders[orderId]?.let { Json.parseToJsonElement(it).jsonObject }
                val eventId = order?.get("event_id")?.jsonPrimitive?.int
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val ownsEvent = profile != null && ownedEvents(owner).any { it["id"]?.jsonPrimitive?.int == eventId }
                when {
                    owner.isEmpty() || owner == "Bearer expired-access" -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                    order == null || !ownsEvent && owner != "Bearer mock-access-token" -> respondJson(MOCK_ORDER_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                    !ownsEvent -> respondJson("""{"error":"EventAccessDeniedError","message":"Only event owner can refund","fields":null}""", HttpStatusCode.Forbidden)
                    profile?.get("status")?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    else -> {
                        val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                        val reason = input["reason"]?.jsonPrimitive?.content.orEmpty()
                        val attempt = refundAttempts.getOrElse(orderId) { 0 } + 1
                        refundAttempts[orderId] = attempt
                        when {
                            reason.length !in 3..500 -> respondJson("""{"error":"VALIDATION_ERROR","message":"Reason must contain 3-500 characters","fields":{"reason":"invalid length"}}""", HttpStatusCode.UnprocessableEntity)
                            order["status"]?.jsonPrimitive?.content != "confirmed" || (order["total_amount"]?.jsonPrimitive?.long ?: 0) <= 0 ->
                                respondJson("""{"error":"OrderNotRefundableError","message":"Only paid confirmed orders can be refunded","fields":null}""", HttpStatusCode.Conflict)
                            reason == "refund-failure" -> respondJson("""{"error":"PaymentFailedError","message":"Simulated refund declined","fields":null}""", HttpStatusCode.PaymentRequired)
                            else -> {
                                val refunded = buildJsonObject {
                                    order.forEach { (k, v) -> put(k, v) }
                                    put("status", "refunded"); put("refunded_at", "2026-11-24T00:00:00+05:00"); put("expires_at", JsonNull)
                                    put("refund",buildJsonObject {
                                        put("amount",order.getValue("total_amount")); put("reason",reason)
                                        put("provider_reference","SIM-R-$orderId"); put("created_at","2026-11-24T00:00:00+05:00")
                                    })
                                    putJsonArray("tickets") { order["tickets"]?.jsonArray.orEmpty().forEach { ticket ->
                                        add(buildJsonObject { ticket.jsonObject.forEach { (k, v) -> put(k, v) }; put("status", "refunded") })
                                    } }
                                }
                                changeInventory(order,false)
                                mockOrders[orderId] = refunded.toString()
                                if (reason == "refund-unknown" && attempt == 1) throw IOException("Mock refund response lost after processing")
                                respondJson(refunded.toString(), HttpStatusCode.OK)
                            }
                        }
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/sales")) -> {
                val owner = authorizationHeader.orEmpty()
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val owned = ownedEvents(owner).any { it["id"]?.jsonPrimitive?.int == id }
                when {
                    owner.isEmpty() || owner == "Bearer expired-access" -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""", HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    !owned -> respondJson("""{"error":"EventAccessDeniedError","message":"Not event owner","fields":null}""", HttpStatusCode.Forbidden)
                    id == 101 && salesReads.getOrElse(id) { 0 } == 0 -> {
                        salesReads[id] = 1
                        respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.ServiceUnavailable)
                    }
                    else -> {
                        val extra = createdTicketTypes[owner].orEmpty()
                        val base = when (id) { 101 -> MOCK_EVENT_101_TICKET_TYPES_JSON; 102 -> MOCK_EVENT_102_TICKET_TYPES_JSON; else -> "[]" }
                        val types = Json.parseToJsonElement(base).jsonArray.map { it.jsonObject }
                            .filter { old -> extra.none { it["id"] == old["id"] } } + extra
                        respondJson(mockSalesSummary(id, mockOrders.values.map { Json.parseToJsonElement(it).jsonObject }, types).toString(), HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/events/\\d+/check-in/undo")) -> {
                val owner = authorizationHeader.orEmpty()
                val userId = when (owner) {
                    "Bearer mock-access-token" -> 100
                    "Bearer mock-empty-access-token" -> 101
                    "Bearer mock-history-offline-token" -> 102
                    else -> owner.removePrefix("Bearer mock-user-").toIntOrNull()?.takeIf { it in registeredUsers.values.indices }?.let { 1000 + it }
                }
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val all = (organizerProfiles.keys + createdEvents.keys).distinct().flatMap { ownedEvents(it) }
                val event = all.firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val isOwner = organizerProfiles.containsKey(owner) && ownedEvents(owner).any { it["id"]?.jsonPrimitive?.int == id }
                when {
                    userId == null -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    event == null -> respondJson("""{"error":"EventNotFoundError","message":"Event not found","fields":null}""", HttpStatusCode.NotFound)
                    !isOwner && eventStaff[id]?.containsKey(userId) != true -> respondJson("""{"error":"NotEventStaffError","message":"Access revoked","fields":null}""", HttpStatusCode.Forbidden)
                    else -> {
                        val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                        val ticketId = input["ticket_id"]?.jsonPrimitive?.int
                        val issued = mockOrders.values.map { Json.parseToJsonElement(it).jsonObject }
                            .filter { it["event_id"]?.jsonPrimitive?.int == id }
                            .flatMap { order -> order["tickets"]?.jsonArray.orEmpty().map { order to it.jsonObject } }
                        val found = issued.firstOrNull { it.second["id"]?.jsonPrimitive?.int == ticketId }
                        when {
                            found == null -> respondJson("""{"error":"TicketNotFoundError","message":"Ticket not found","fields":null}""", HttpStatusCode.NotFound)
                            found.second["status"]?.jsonPrimitive?.content != "checked_in" -> respondJson("""{"error":"TicketNotCheckedInError","message":"Check-in is absent","fields":null}""", HttpStatusCode.Conflict)
                            else -> {
                                val (order, original) = found
                                val updated = buildJsonObject {
                                    original.forEach { (k, v) -> put(k, v) }
                                    put("status", "valid"); put("checked_in_at", JsonNull)
                                }
                                mockOrders[order.getValue("id").jsonPrimitive.int] = buildJsonObject {
                                    order.forEach { (k, v) -> put(k, v) }
                                    putJsonArray("tickets") { order.getValue("tickets").jsonArray.forEach { old -> add(if (old.jsonObject["id"] == original["id"]) updated else old) } }
                                }.toString()
                                if (order["buyer_name"]?.jsonPrimitive?.content == "undo-unknown" && lostUndoResponses.add(requireNotNull(ticketId)))
                                    throw IOException("Mock undo response lost after update")
                                respondJson(scanTicket(updated).toString(), HttpStatusCode.OK)
                            }
                        }
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/events/\\d+/check-in")) -> {
                val owner = authorizationHeader.orEmpty()
                val userId = when (owner) {
                    "Bearer mock-access-token" -> 100
                    "Bearer mock-empty-access-token" -> 101
                    "Bearer mock-history-offline-token" -> 102
                    else -> owner.removePrefix("Bearer mock-user-").toIntOrNull()?.takeIf { it in registeredUsers.values.indices }?.let { 1000 + it }
                }
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val all = (organizerProfiles.keys + createdEvents.keys).distinct().flatMap { ownedEvents(it) }
                val event = all.firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val isOwner = organizerProfiles.containsKey(owner) && ownedEvents(owner).any { it["id"]?.jsonPrimitive?.int == id }
                when {
                    userId == null -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    event == null -> respondJson("""{"error":"EventNotFoundError","message":"Event not found","fields":null}""", HttpStatusCode.NotFound)
                    !isOwner && eventStaff[id]?.containsKey(userId) != true -> respondJson("""{"error":"NotEventStaffError","message":"Access revoked","fields":null}""", HttpStatusCode.Forbidden)
                    else -> {
                        val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                        val code = input["code"]?.jsonPrimitive?.content.orEmpty()
                        val issued = mockOrders.values.map { Json.parseToJsonElement(it).jsonObject }
                            .flatMap { order -> order["tickets"]?.jsonArray.orEmpty().map { order to it.jsonObject } }
                        val found = issued.firstOrNull { it.second["code"]?.jsonPrimitive?.content == code }
                        val order = found?.first
                        var ticket = found?.second
                        val result = when {
                            ticket == null -> "invalid"
                            order?.get("event_id")?.jsonPrimitive?.int != id -> "wrong_event"
                            event["status"]?.jsonPrimitive?.content == "cancelled" -> "event_cancelled"
                            ticket["status"]?.jsonPrimitive?.content == "checked_in" -> "already_checked_in"
                            ticket["status"]?.jsonPrimitive?.content == "cancelled" -> "cancelled"
                            ticket["status"]?.jsonPrimitive?.content == "refunded" -> "refunded"
                            ticket["status"]?.jsonPrimitive?.content == "valid" -> "checked_in"
                            else -> "invalid"
                        }
                        if (result == "checked_in") {
                            val original = requireNotNull(ticket)
                            ticket = buildJsonObject {
                                original.forEach { (k, v) -> put(k, v) }
                                put("status", "checked_in"); put("checked_in_at", "2026-11-23T12:00:00+05:00")
                            }
                            val originalOrder = requireNotNull(order)
                            mockOrders[originalOrder.getValue("id").jsonPrimitive.int] = buildJsonObject {
                                originalOrder.forEach { (k, v) -> put(k, v) }
                                putJsonArray("tickets") { originalOrder.getValue("tickets").jsonArray.forEach { old -> add(if (old.jsonObject["id"] == original["id"]) requireNotNull(ticket) else old) } }
                            }.toString()
                            if (originalOrder["buyer_name"]?.jsonPrimitive?.content == "checkin-unknown" && lostCheckInResponses.add(original.getValue("id").jsonPrimitive.int))
                                throw IOException("Mock check-in response lost after entry registered")
                        }
                        respondJson(buildJsonObject {
                            put("result", result); put("message", result)
                            put("ticket", if (result in listOf("invalid", "wrong_event")) JsonNull else ticket?.let { scanTicket(it) } ?: JsonNull)
                        }.toString(), HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/check-in/(stats|search)")) -> {
                val owner = authorizationHeader.orEmpty()
                val userId = when (owner) {
                    "Bearer mock-access-token" -> 100
                    "Bearer mock-empty-access-token" -> 101
                    "Bearer mock-history-offline-token" -> 102
                    else -> owner.removePrefix("Bearer mock-user-").toIntOrNull()?.takeIf { it in registeredUsers.values.indices }?.let { 1000 + it }
                }
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val all = (organizerProfiles.keys + createdEvents.keys).distinct().flatMap { ownedEvents(it) }
                val event = all.firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val isOwner = organizerProfiles.containsKey(owner) && ownedEvents(owner).any { it["id"]?.jsonPrimitive?.int == id }
                val q = request.url.parameters["q"].orEmpty()
                val isSearch = path.endsWith("/search")
                val key = "$id/${if (isSearch) "search" else "stats"}"
                when {
                    userId == null -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    event == null -> respondJson("""{"error":"EventNotFoundError","message":"Event not found","fields":null}""", HttpStatusCode.NotFound)
                    !isOwner && eventStaff[id]?.containsKey(userId) != true -> respondJson("""{"error":"NotEventStaffError","message":"Access revoked","fields":null}""", HttpStatusCode.Forbidden)
                    isSearch && q.length !in 2..100 -> respondJson("""{"error":"VALIDATION_ERROR","message":"q must contain 2-100 characters","fields":{"q":"invalid length"}}""", HttpStatusCode.UnprocessableEntity)
                    id == 101 && checkInReads.getOrElse(key) { 0 } == 0 -> {
                        checkInReads[key] = 1
                        respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.ServiceUnavailable)
                    }
                    else -> {
                        val issued = mockOrders.values.map { Json.parseToJsonElement(it).jsonObject }
                            .filter { it["event_id"]?.jsonPrimitive?.int == id }
                            .flatMap { order -> order["tickets"]?.jsonArray.orEmpty().map { order to it.jsonObject } }
                        if (!isSearch) {
                            fun count(status: String) = issued.count { it.second["status"]?.jsonPrimitive?.content == status }
                            respondJson(buildJsonObject {
                                put("registered", issued.size); put("checked_in", count("checked_in"))
                                put("not_checked_in", count("valid")); put("cancelled", count("cancelled")); put("refunded", count("refunded"))
                            }.toString(), HttpStatusCode.OK)
                        } else {
                            val matched = issued.filter { (order, ticket) ->
                                ticket["attendee_name"]?.jsonPrimitive?.content?.contains(q, ignoreCase = true) == true ||
                                    order["buyer_email"]?.jsonPrimitive?.content?.contains(q, ignoreCase = true) == true ||
                                    ticket["code"]?.jsonPrimitive?.content == q
                            }.take(20)
                            respondJson(buildJsonArray {
                                matched.forEach { (_, ticket) ->
                                    add(scanTicket(ticket))
                                }
                            }.toString(), HttpStatusCode.OK)
                        }
                    }
                }
            }
            request.method == HttpMethod.Get && path == "/staff/events" -> {
                val owner = authorizationHeader.orEmpty()
                val userId = when (owner) {
                    "Bearer mock-access-token" -> 100
                    "Bearer mock-empty-access-token" -> 101
                    "Bearer mock-history-offline-token" -> 102
                    else -> owner.removePrefix("Bearer mock-user-").toIntOrNull()?.takeIf { it in registeredUsers.values.indices }?.let { 1000 + it }
                }
                if (userId == null) respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                else {
                    val attempt = scannerReads.getOrElse(owner) { 0 } + 1
                    scannerReads[owner] = attempt
                    if (owner == "Bearer mock-access-token" && attempt == 1 || owner == "Bearer mock-history-offline-token") {
                        respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.ServiceUnavailable)
                    } else {
                        val allEvents = (organizerProfiles.keys + createdEvents.keys).distinct().flatMap { ownedEvents(it) }
                        val ownedIds = ownedEvents(owner).map { it.getValue("id").jsonPrimitive.int }.toSet()
                        val assignedIds = eventStaff.filterValues { it.containsKey(userId) }.keys
                        val accessible = allEvents.filter {
                            it.getValue("id").jsonPrimitive.int in (ownedIds + assignedIds) && it["status"]?.jsonPrimitive?.content != "draft"
                        }.distinctBy { it.getValue("id") }.sortedBy { Instant.parse(it.getValue("start_time").jsonPrimitive.content) }
                        respondJson(buildJsonArray { accessible.forEach { add(it) } }.toString(), HttpStatusCode.OK)
                    }
                }
            }
            path.matches(Regex("/events/\\d+/staff(?:/\\d+)?")) && request.method in listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete) -> {
                val owner = authorizationHeader
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val event = createdEvents[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val owned = event != null || (owner == "Bearer mock-access-token" && id in 101..103)
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val title = event?.get("title")?.jsonPrimitive?.content
                when {
                    owner == null || owner == "Bearer expired-access" -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    !owned -> respondJson("""{"error":"EventAccessDeniedError","message":"Not event owner","fields":null}""", HttpStatusCode.Forbidden)
                    profile?.get("status")?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    request.method == HttpMethod.Get -> {
                        val attempt = staffReads.getOrElse(id) { 0 } + 1
                        staffReads[id] = attempt
                        if (id == 101 && attempt == 1) respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.ServiceUnavailable)
                        else respondJson(buildJsonArray { eventStaff[id].orEmpty().values.forEach { add(it) } }.toString(), HttpStatusCode.OK)
                    }
                    request.method == HttpMethod.Delete -> {
                        val userId = path.substringAfterLast('/').toInt()
                        if (eventStaff[id]?.remove(userId) == null) respondJson("""{"error":"StaffAssignmentNotFoundError","message":"Assignment not found","fields":null}""", HttpStatusCode.NotFound)
                        else {
                            if (title == "staff-unknown" && unknownStaffRemovals.add(id)) throw IOException("Mock removal response lost")
                            respond("", HttpStatusCode.NoContent)
                        }
                    }
                    else -> {
                        val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                        val email = input["email"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()
                        val known = listOf("visitor@biletflow.kz", "empty@biletflow.kz", "history-offline@biletflow.kz")
                        val userId = if (email in known) 100 + known.indexOf(email)
                            else registeredUsers.keys.indexOf(email).takeIf { it >= 0 }?.let { 1000 + it }
                        when {
                            email == "field-error@biletflow.kz" || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) ->
                                respondJson("""{"error":"VALIDATION_ERROR","message":"Invalid email","fields":{"email":"Invalid test email"}}""", HttpStatusCode.UnprocessableEntity)
                            userId == null -> respondJson("""{"error":"UserNotFoundError","message":"User not found","fields":null}""", HttpStatusCode.NotFound)
                            eventStaff[id]?.containsKey(userId) == true -> respondJson("""{"error":"StaffAlreadyAssignedError","message":"Already assigned","fields":null}""", HttpStatusCode.Conflict)
                            else -> {
                                val staff = buildJsonObject {
                                    put("user_id", userId); put("email", email); put("created_at", "2026-11-23T10:00:00+05:00")
                                }
                                eventStaff.getOrPut(id) { mutableMapOf() }[userId] = staff
                                if (title == "staff-unknown" && unknownStaffAssignments.add(id)) throw IOException("Mock assignment response lost")
                                respondJson(staff.toString(), HttpStatusCode.Created)
                            }
                        }
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/attendees")) -> {
                val owner = authorizationHeader
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val event = createdEvents[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val owned = event != null || (owner == "Bearer mock-access-token" && id in 101..103)
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    owner == null || owner == "Bearer expired-access" -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    !owned -> respondJson("""{"error":"EventAccessDeniedError","message":"Not event owner","fields":null}""", HttpStatusCode.Forbidden)
                    profile?.get("status")?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    id == 101 && attendeeReads.getOrElse(id) { 0 } == 0 -> {
                        attendeeReads[id] = 1
                        respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.ServiceUnavailable)
                    }
                    else -> {
                        val types = (Json.parseToJsonElement(MOCK_EVENT_101_TICKET_TYPES_JSON).jsonArray +
                            Json.parseToJsonElement(MOCK_EVENT_102_TICKET_TYPES_JSON).jsonArray).map { it.jsonObject } + createdTicketTypes.values.flatten()
                        respondJson(buildJsonArray {
                            mockOrders.values.forEach { orderJson ->
                                val order = Json.parseToJsonElement(orderJson).jsonObject
                                if (order["event_id"]?.jsonPrimitive?.int == id) {
                                    order["tickets"]?.jsonArray.orEmpty().forEach { value ->
                                        val ticket = value.jsonObject
                                        val typeId = ticket["ticket_type_id"]?.jsonPrimitive?.int
                                        val type = types.lastOrNull { it["id"]?.jsonPrimitive?.int == typeId }
                                        add(buildJsonObject {
                                            put("ticket_id", ticket.getValue("id"))
                                            put("attendee_name", ticket.getValue("attendee_name"))
                                            put("ticket_type_name", type?.get("name") ?: JsonPrimitive("Ticket #$typeId"))
                                            put("status", ticket.getValue("status")); put("order_id", order.getValue("id"))
                                            put("buyer_email", order.getValue("buyer_email")); put("created_at", ticket.getValue("created_at"))
                                        })
                                    }
                                }
                            }
                        }.toString(), HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/events/\\d+/paid-sales/(accept-terms|pay-fee|activate)")) -> {
                val owner = authorizationHeader
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val action = path.substringAfterLast('/')
                val event = createdEvents[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val owned = event != null || (owner == "Bearer mock-access-token" && id in 101..103)
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val current = paidStatus(owner, id)
                when {
                    owner == null || owner == "Bearer expired-access" -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    !owned -> respondJson("""{"error":"EventAccessDeniedError","message":"Not event owner","fields":null}""", HttpStatusCode.Forbidden)
                    profile?.get("status")?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    current["status"]?.jsonPrimitive?.content in listOf("active", "suspended") -> respondJson("""{"error":"InvalidActivationStatusError","message":"Invalid activation status","fields":null}""", HttpStatusCode.Conflict)
                    action == "activate" && current["can_activate"]?.jsonPrimitive?.content != "true" -> respondJson("""{"error":"ActivationRequirementsNotMetError","message":"Complete the checklist","fields":null}""", HttpStatusCode.Conflict)
                    action == "pay-fee" && id in paidFees -> respondJson("""{"error":"ActivationFeeAlreadyPaidError","message":"Fee already paid","fields":null}""", HttpStatusCode.Conflict)
                    else -> {
                        if (action == "accept-terms") {
                            acceptedTerms.add(id)
                            respondJson(paidStatus(owner, id).toString(), HttpStatusCode.OK)
                        } else if (action == "activate") {
                            activeSales.add(id)
                            respondJson(paidStatus(owner, id).toString(), HttpStatusCode.OK)
                        } else {
                            val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                            val attempt = feeAttempts.getOrElse(id) { 0 } + 1
                            feeAttempts[id] = attempt
                            val title = event?.get("title")?.jsonPrimitive?.content
                            when {
                                input["simulate_outcome"]?.jsonPrimitive?.content == "failure" || title == "fee-failure" && attempt == 1 ->
                                    respondJson("""{"error":"PaymentFailedError","message":"Simulated payment declined","fields":null}""", HttpStatusCode.PaymentRequired)
                                else -> {
                                    paidFees.add(id)
                                    if (title == "fee-unknown" && attempt == 1) throw IOException("Mock fee response lost after payment")
                                    respondJson(buildJsonObject {
                                        put("id", 9000 + id); put("purpose", "activation_fee"); put("amount", 5000)
                                        put("currency", "KZT"); put("status", "succeeded"); put("provider", "simulated")
                                        put("provider_reference", "mock-fee-$id"); put("is_simulated", true)
                                        put("created_at", "2026-11-23T10:00:00+05:00")
                                    }.toString(), HttpStatusCode.OK)
                                }
                            }
                        }
                    }
                }
            }
            (request.method == HttpMethod.Post && path == "/organizers/me/verification") ||
                (request.method == HttpMethod.Put && path == "/organizers/me/payout-account") -> {
                val owner = authorizationHeader
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    owner == null || owner == "Bearer expired-access" -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""", HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    else -> {
                        val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                        fun value(key: String) = input[key]?.jsonPrimitive?.content.orEmpty().trim()
                        val verify = path.endsWith("/verification")
                        val marker = value(if (verify) "legal_name" else "holder_name")
                        val errors = buildJsonObject {
                            val nameKey = if (verify) "legal_name" else "holder_name"
                            if (value(nameKey).length !in 2..200) put(nameKey, "Enter 2–200 characters")
                            if (!verify && value("bank_name").length !in 2..100) put("bank_name", "Enter 2–100 characters")
                            val key = if (verify) "contact_phone" else "iban"
                            if (value(key).isBlank() || value(key) == "field-error") put(key, "Invalid test value")
                        }
                        if (errors.isNotEmpty()) respondJson(buildJsonObject {
                            put("error", "ValidationError"); put("message", "Invalid fields"); put("fields", errors)
                        }.toString(), HttpStatusCode.UnprocessableEntity)
                        else {
                            if (marker == "network-error") throw IOException("Mock organizer connection unavailable")
                            val updated = buildJsonObject {
                                profile.forEach { (key, value) -> put(key, value) }
                                if (verify) {
                                    put("legal_name", value("legal_name")); put("contact_phone", value("contact_phone"))
                                    put("identity_verified_at", "2026-11-23T10:00:00+05:00")
                                } else putJsonObject("payout_account") {
                                    put("holder_name", value("holder_name")); put("bank_name", value("bank_name"))
                                    put("iban_last4", value("iban").filterNot { it.isWhitespace() }.takeLast(4))
                                    put("is_simulated", true)
                                }
                            }
                            organizerProfiles[owner!!] = updated.toString()
                            if (marker == "unknown-result") throw IOException("Mock response lost after save")
                            respondJson(updated.toString(), HttpStatusCode.OK)
                        }
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/paid-sales")) -> {
                val owner = authorizationHeader
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val event = createdEvents[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val owned = event != null || (owner == "Bearer mock-access-token" && id in 101..103)
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    owner == null || owner == "Bearer expired-access" -> respondJson("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                    !owned -> respondJson("""{"error":"EventAccessDeniedError","message":"Not event owner","fields":null}""", HttpStatusCode.Forbidden)
                    profile?.get("status")?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Organizer not approved","fields":null}""", HttpStatusCode.Forbidden)
                    event?.get("title")?.jsonPrimitive?.content == "paid-sales-missing" -> respondJson("""{"error":"PaidSalesNotFoundError","message":"Activation record not found","fields":null}""", HttpStatusCode.NotFound)
                    id == 101 && paidSalesReads.getOrPut(id) { 0 } == 0 -> {
                        paidSalesReads[id] = 1
                        respondJson("""{"detail":"Temporary unavailable"}""", HttpStatusCode.ServiceUnavailable)
                    }
                    else -> respondJson(paidStatus(owner, id).toString(), HttpStatusCode.OK)
                }
            }
            request.method == HttpMethod.Patch && path.matches(Regex("/ticket_types/\\d+/(hide|unhide)")) -> {
                val owner = authorizationHeader
                val id = path.removePrefix("/ticket_types/").substringBefore('/').toInt()
                val original = createdTicketTypes[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                    ?: if(owner == "Bearer mock-access-token") (Json.parseToJsonElement(MOCK_EVENT_101_TICKET_TYPES_JSON).jsonArray + Json.parseToJsonElement(MOCK_EVENT_102_TICKET_TYPES_JSON).jsonArray).firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.int == id }?.jsonObject else null
                if(original == null) respondJson("""{"error":"EventAccessDeniedError","message":"Access denied","fields":null}""",HttpStatusCode.Forbidden)
                else {
                    val updated = buildJsonObject { original.forEach { (k,v) -> put(k,v) }; put("is_hidden",path.endsWith("/hide")) }
                    val list = createdTicketTypes.getOrPut(owner!!) { mutableListOf() }
                    list.removeAll { it["id"]?.jsonPrimitive?.int == id }; list.add(updated)
                    respondJson(updated.toString(),HttpStatusCode.OK)
                }
            }
            request.method == HttpMethod.Patch && path.matches(Regex("/ticket-types/\\d+")) -> {
                val owner = authorizationHeader
                val id = path.substringAfterLast('/').toInt()
                val original = createdTicketTypes[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                    ?: if(owner == "Bearer mock-access-token") (Json.parseToJsonElement(MOCK_EVENT_101_TICKET_TYPES_JSON).jsonArray + Json.parseToJsonElement(MOCK_EVENT_102_TICKET_TYPES_JSON).jsonArray).firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.int == id }?.jsonObject else null
                if(original == null) respondJson("""{"error":"EventAccessDeniedError","message":"Access denied","fields":null}""",HttpStatusCode.Forbidden)
                else {
                    val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                    val updated = buildJsonObject { original.forEach { (k,v) -> put(k,v) }; input.forEach { (k,v) -> put(k,v) } }
                    if(updated["quantity"]!!.jsonPrimitive.int < original["sold_count"]!!.jsonPrimitive.int)
                        respondJson("""{"error":"QuantityBelowSoldError","message":"Quantity below sold","fields":{"quantity":"too small"}}""",HttpStatusCode.Conflict)
                    else {
                        val list = createdTicketTypes.getOrPut(owner!!) { mutableListOf() }
                        list.removeAll { it["id"]?.jsonPrimitive?.int == id }; list.add(updated)
                        respondJson(updated.toString(),HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Patch && path.matches(Regex("/events/\\d+/(publish|unpublish|cancel)")) -> {
                val owner = authorizationHeader
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val original = createdEvents[owner].orEmpty().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val publish = path.endsWith("/publish")
                val cancel = path.endsWith("/cancel")
                when {
                    original == null -> respondJson("""{"error":"EventAccessDeniedError","message":"Access denied","fields":null}""",HttpStatusCode.Forbidden)
                    (if(cancel) original["status"]?.jsonPrimitive?.content !in listOf("draft","published") else original["status"]?.jsonPrimitive?.content != (if (publish) "draft" else "published")) -> respondJson("""{"error":"InvalidEventStatusError","message":"Event status changed","fields":null}""",HttpStatusCode.Conflict)
                    publish && (original["venue_name"]==null || original["venue_name"]==JsonNull || original["venue_address"]==null || original["venue_address"]==JsonNull) -> respondJson("""{"error":"EventNotReadyToPublishError","message":"Venue and address are required","fields":{"venue_name":"required","venue_address":"required"}}""",HttpStatusCode.BadRequest)
                    else -> {
                        val updated = buildJsonObject {
                            original.forEach { (k,v) -> put(k,v) }
                            put("status",if(cancel) "cancelled" else if(publish) "published" else "draft")
                        }
                        createdEvents[owner]?.removeAll { it["id"]?.jsonPrimitive?.int == id }
                        createdEvents[owner]?.add(updated)
                        if(cancel) mockOrders.toMap().forEach { (orderId,json) ->
                            val order = Json.parseToJsonElement(json).jsonObject
                            if(order["event_id"]?.jsonPrimitive?.int == id && order["status"]?.jsonPrimitive?.content in listOf("pending","confirmed")) {
                                val refunded = order["status"]?.jsonPrimitive?.content=="confirmed" && order["total_amount"]!!.jsonPrimitive.long>0
                                changeInventory(order,false)
                                mockOrders[orderId] = buildJsonObject {
                                    order.forEach { (k,v) -> put(k,v) }
                                    put("status",if(refunded) "refunded" else "cancelled")
                                    put(if(refunded) "refunded_at" else "cancelled_at","2026-11-23T10:00:00+05:00")
                                    put("tickets",buildJsonArray { order["tickets"]?.jsonArray.orEmpty().forEach { ticket ->
                                        add(buildJsonObject { ticket.jsonObject.forEach { (k,v) -> put(k,v) }; put("status",if(refunded) "refunded" else "cancelled") })
                                    } })
                                }.toString()
                            }
                        }
                        if (original["title"]?.jsonPrimitive?.content == "status-offline") throw IOException("Mock response lost after status change")
                        respondJson(updated.toString(),HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/events/\\d+/ticket-type")) -> {
                val owner = authorizationHeader
                val eventId = path.removePrefix("/events/").substringBefore('/').toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val owned = createdEvents[owner].orEmpty().any { it["id"]?.jsonPrimitive?.int == eventId } ||
                    (owner == "Bearer mock-access-token" && eventId in 101..103)
                if (profile == null || !owned) respondJson("""{"error":"EventAccessDeniedError","message":"Access denied","fields":null}""",HttpStatusCode.Forbidden)
                else {
                    val input = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                    val values = input.mapValues { it.value.jsonPrimitive.content }
                    val errors = com.example.biletflow.presentation.organizer.validateTicketType(values)
                    if (errors.isNotEmpty()) respondJson(buildJsonObject {
                        put("error","VALIDATION_ERROR"); put("message","Invalid ticket fields")
                        put("fields",buildJsonObject { errors.forEach { put(it,"invalid") } })
                    }.toString(),HttpStatusCode.UnprocessableEntity)
                    else {
                        val ticket = buildJsonObject {
                            input.forEach { (k,v) -> put(k,v) }
                            put("id",nextTicketTypeId++); put("event_id",eventId)
                            put("is_hidden",false); put("sold_count",0)
                            if (input["max_per_order"] == null) put("max_per_order",10)
                        }
                        createdTicketTypes.getOrPut(owner!!) { mutableListOf() }.add(ticket)
                        respondJson(ticket.toString(),HttpStatusCode.Created)
                    }
                }
            }
            request.method == HttpMethod.Patch && path.matches(Regex("/events/\\d+")) -> {
                val owner = authorizationHeader
                val id = path.substringAfterLast('/').toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val ownCreated = createdEvents[owner]?.firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val seeded = if (owner == "Bearer mock-access-token") mockEvents.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.int == id }?.jsonObject else null
                val original = ownCreated ?: seeded
                if (profile == null || original == null) respondJson("""{"error":"EventAccessDeniedError","message":"Access denied","fields":null}""", HttpStatusCode.Forbidden)
                else {
                    val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                    val input = Json.parseToJsonElement(body).jsonObject
                    val changed = buildJsonObject {
                        original.forEach { (k,v) -> put(k,v) }
                        input.forEach { (k,v) -> put(k,v) }
                        put("organizer_id",profile["id"]!!)
                    }
                    if (input["title"] == JsonPrimitive("")) respondJson("""{"detail":[{"loc":["body","title"],"msg":"Title required"}]}""",HttpStatusCode.UnprocessableEntity)
                    else {
                        val list = createdEvents.getOrPut(owner!!) { mutableListOf() }
                        list.removeAll { it["id"]?.jsonPrimitive?.int == id }
                        list.add(changed)
                        respondJson(changed.toString(),HttpStatusCode.OK)
                    }
                }
            }
            path == "/events" && request.method == HttpMethod.Post -> {
                val owner = authorizationHeader.orEmpty()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Not found","fields":null}""", HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Not approved","fields":null}""", HttpStatusCode.Forbidden)
                    else -> {
                        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                        val input = Json.parseToJsonElement(body).jsonObject
                        val event = buildJsonObject {
                            input.forEach { (key,value) -> put(key,value) }
                            put("id", nextEventId++)
                            put("organizer_id", profile["id"]!!)
                            put("status", "draft")
                            if (!input.containsKey("visibility")) put("visibility","public")
                        }
                        createdEvents.getOrPut(owner) { mutableListOf() }.add(event)
                        respondJson(event.toString(), HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Get && path == "/events/me" -> {
                val owner = authorizationHeader.orEmpty()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""", HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Approval required","fields":null}""", HttpStatusCode.Forbidden)
                    else -> {
                        val attempt = myEventReads.getOrElse(owner) { 0 } + 1
                        myEventReads[owner] = attempt
                        if (attempt == 1) respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.InternalServerError)
                        else respondJson(buildJsonArray {
                            ownedEvents(owner).forEach { add(it) }
                        }.toString(), HttpStatusCode.OK)
                    }
                }
            }
            path == "/organizers/me" || path == "/organizers/apply" -> {
                val owner = authorizationHeader
                if (owner == null || !(owner == "Bearer mock-access-token" || owner == "Bearer mock-empty-access-token" || owner.startsWith("Bearer mock-user-"))) {
                    respondJson("""{"detail":"Invalid access token"}""", HttpStatusCode.Unauthorized)
                } else if (request.method == HttpMethod.Post && path == "/organizers/apply") {
                    if (organizerProfiles.containsKey(owner)) respondJson("""{"error":"OrganizerProfileAlreadyExistsError","message":"Profile exists","fields":null}""", HttpStatusCode.Conflict)
                    else {
                        val userId = when (owner) {
                            "Bearer mock-access-token" -> 100
                            "Bearer mock-empty-access-token" -> 101
                            "Bearer mock-history-offline-token" -> 102
                            else -> 1000 + (owner.removePrefix("Bearer mock-user-").toIntOrNull() ?: 0)
                        }
                        val profile = """{"id":${if(owner=="Bearer mock-access-token") 31 else 32 + organizerProfiles.size},"user_id":$userId,"status":"pending"}"""
                        organizerProfiles[owner] = profile
                        organizerReads[owner] = 0
                        respondJson(profile, HttpStatusCode.OK)
                    }
                } else {
                    val reads = organizerReads.getOrElse(owner) { 0 } + 1
                    organizerReads[owner] = reads
                    // Debug approval on third explicit reload after applying; first reload fails.
                    when {
                        organizerProfiles[owner] == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""", HttpStatusCode.NotFound)
                        reads == 1 -> respondJson("""{"detail":"Temporarily unavailable"}""", HttpStatusCode.InternalServerError)
                        else -> {
                            if (reads >= 3) organizerProfiles[owner] = buildJsonObject {
                                Json.parseToJsonElement(organizerProfiles.getValue(owner)).jsonObject.forEach { (key, value) -> put(key, value) }
                                put("status", "approved")
                            }.toString()
                            respondJson(organizerProfiles.getValue(owner), HttpStatusCode.OK)
                        }
                    }
                }
            }
            request.method == HttpMethod.Post && path == "/auth/refresh" -> {
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                val refresh = Json.parseToJsonElement(body).jsonObject["refresh_token"]?.jsonPrimitive?.content.orEmpty()
                val owner = refreshOwners[refresh] ?: refresh.removePrefix("mock-refresh-")
                    .toIntOrNull()?.takeIf { it in registeredUsers.values.indices }?.let { "Bearer mock-user-$it" }
                if (owner == null || !consumedRefreshTokens.add(refresh)) {
                    respondJson("""{"error":"InvalidRefreshTokenError","message":"Invalid refresh token","fields":null}""", HttpStatusCode.Unauthorized)
                } else {
                    rotation++
                    val access = "mock-rotated-access-$rotation"
                    accessAliases["Bearer $access"] = owner
                    refreshOwners["mock-rotated-refresh-$rotation"] = owner
                    respondJson(buildJsonObject {
                        put("access_token", access)
                        put("refresh_token", "mock-rotated-refresh-$rotation")
                        put("token_type", "bearer")
                    }.toString(), HttpStatusCode.OK)
                }
            }
            request.method == HttpMethod.Post && path == "/auth/logout" -> {
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                val refresh = Json.parseToJsonElement(body).jsonObject["refresh_token"]?.jsonPrimitive?.content
                if (refresh == "mock-history-offline-refresh-token") throw IOException("Mock logout unavailable")
                respondJson("{}", HttpStatusCode.OK)
            }
            request.method == HttpMethod.Post && path == "/auth/register" -> {
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                val payload = Json.parseToJsonElement(body).jsonObject
                val email = payload["email"]?.jsonPrimitive?.content.orEmpty()
                val password = payload["password"]?.jsonPrimitive?.content.orEmpty()
                when {
                    email == "register-offline@biletflow.kz" -> throw IOException("Mock registration unavailable")
                    email in registeredUsers || email in setOf("visitor@biletflow.kz", "empty@biletflow.kz", "history-offline@biletflow.kz", "attendee@biletflow.kz") ->
                        respondJson("""{"error":"UserAlreadyExistsError","message":"User already exists","fields":null}""", HttpStatusCode.Conflict)
                    password.length < 6 || !email.contains('@') ->
                        respondJson(MOCK_ORDER_VALIDATION_JSON, HttpStatusCode.UnprocessableEntity)
                    else -> {
                        registeredUsers[email] = password
                        respondJson(buildJsonObject { put("id", 999 + registeredUsers.size); put("email", email) }.toString(), HttpStatusCode.Created)
                    }
                }
            }
            request.method == HttpMethod.Post && path == "/auth/login" -> {
                val body = (request.body as? OutgoingContent.ByteArrayContent)
                    ?.bytes()
                    ?.decodeToString()
                    .orEmpty()
                val credentials = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                val email = credentials?.get("email")?.jsonPrimitive?.content
                val password = credentials?.get("password")?.jsonPrimitive?.content
                when {
                    email != null && registeredUsers[email] == password && "Bearer mock-user-${registeredUsers.keys.indexOf(email)}" in suspendedOwners ->
                        respondJson("""{"error":"UserSuspendedError","message":"Account suspended","fields":null}""",HttpStatusCode.Forbidden)
                    email != null && registeredUsers[email] == password -> respondJson(
                        buildJsonObject {
                            put("access_token", "mock-user-${registeredUsers.keys.indexOf(email)}")
                            put("refresh_token", "mock-refresh-${registeredUsers.keys.indexOf(email)}")
                            put("token_type", "bearer")
                        }.toString(), HttpStatusCode.OK,
                    )
                    email == "suspended@biletflow.kz" && password == "password123" -> respondJson("""{"error":"UserSuspendedError","message":"Account suspended","fields":null}""",HttpStatusCode.Forbidden)
                    email == "offline@biletflow.kz" -> throw IOException("Mock network unavailable")
                                        email == "attendee@biletflow.kz" && password == "password123" -> respondJson("""{"access_token":"mock-attendee-access-token","refresh_token":"mock-attendee-refresh-token","token_type":"bearer"}""",HttpStatusCode.OK)
                    email == "visitor@biletflow.kz" && password == "password123" ->
                        respondJson(MOCK_LOGIN_SUCCESS_JSON, HttpStatusCode.OK)
                    email == "empty@biletflow.kz" && password == "password123" ->
                        respondJson(MOCK_EMPTY_LOGIN_SUCCESS_JSON, HttpStatusCode.OK)
                    email == "history-offline@biletflow.kz" && password == "password123" ->
                        respondJson(MOCK_HISTORY_OFFLINE_LOGIN_SUCCESS_JSON, HttpStatusCode.OK)
                    else -> respondJson(MOCK_INVALID_CREDENTIALS_JSON, HttpStatusCode.Unauthorized)
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/events/\\d+/orders")) -> {
                val authorization = authorizationHeader
                if (!isBuyer(authorization)) {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val body = (request.body as? OutgoingContent.ByteArrayContent)
                        ?.bytes()
                        ?.decodeToString()
                        .orEmpty()
                    val payload = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    val buyerName = payload?.get("buyer_name")?.jsonPrimitive?.content.orEmpty()
                    val items = payload?.get("items")?.jsonArray.orEmpty().mapNotNull { element ->
                        val item = element.jsonObject
                        val ticketTypeId = item["ticket_type_id"]?.jsonPrimitive?.int
                        val quantity = item["quantity"]?.jsonPrimitive?.int
                        if (ticketTypeId != null && quantity != null) ticketTypeId to quantity else null
                    }
                    val eventId = path.removePrefix("/events/").substringBefore('/').toInt()
                    val ticketPrices = mapOf(1001 to 5000, 1002 to 0, 2001 to 0, 2002 to 1000) +
                        createdTicketTypes.values.flatten().associate { it["id"]!!.jsonPrimitive.int to it["price"]!!.jsonPrimitive.int }
                    val eventOwner = createdEvents.entries.firstOrNull { (_, events) -> events.any { it["id"]?.jsonPrimitive?.int == eventId } }?.key
                        ?: "Bearer mock-access-token"
                    val event=eventById(eventId)
                    val types=eventTypes(eventId).associateBy {it.getValue("id").jsonPrimitive.int}
                    fun instant(key:String)=event?.get(key)?.takeIf {it!=JsonNull}?.jsonPrimitive?.content?.let {Instant.parse(it)}
                    when {
                        !publicPage(event) -> respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                        (instant("registration_opens_at")?.let {now<it}==true || now>(instant("registration_closes_at") ?: instant("end_time")!!)) -> respondJson("""{"error":"RegistrationClosedError","message":"Registration is closed"}""",HttpStatusCode.Conflict)
                        items.map {it.first}.distinct().size!=items.size || items.any {it.second<=0} -> respondJson("""{"error":"InvalidOrderQuantityError","message":"Invalid quantity"}""",HttpStatusCode.BadRequest)
                        items.any { (id,_) -> val type=types[id]; type==null || type["is_hidden"]?.jsonPrimitive?.content=="true" || now<Instant.parse(type.getValue("sales_start").jsonPrimitive.content) || now>Instant.parse(type.getValue("sales_end").jsonPrimitive.content) } -> respondJson("""{"error":"TicketTypeNotAvailableError","message":"Ticket type unavailable"}""",HttpStatusCode.Conflict)
                        items.any { (id,n) -> n>types.getValue(id).getValue("max_per_order").jsonPrimitive.int } -> respondJson("""{"error":"InvalidOrderQuantityError","message":"Order limit exceeded"}""",HttpStatusCode.BadRequest)
                        items.any { (id,n) -> val t=types.getValue(id);n>t.getValue("quantity").jsonPrimitive.int-t.getValue("sold_count").jsonPrimitive.int } -> respondJson(MOCK_ORDER_CONFLICT_JSON,HttpStatusCode.Conflict)
                        buyerName.equals("offline", ignoreCase = true) ->
                            throw IOException("Mock connection interrupted after sending order")
                        buyerName.equals("conflict", ignoreCase = true) ->
                            respondJson(MOCK_ORDER_CONFLICT_JSON, HttpStatusCode.Conflict)
                        buyerName.isBlank() || items.isEmpty() ->
                            respondJson(MOCK_ORDER_VALIDATION_JSON, HttpStatusCode.UnprocessableEntity)
                        items.any { (id, _) -> (ticketPrices[id] ?: 0) > 0 } && paidStatus(eventOwner, eventId)["status"]?.jsonPrimitive?.content != "active" ->
                            respondJson("""{"error":"PaidSalesNotActiveError","message":"Paid sales are not active","fields":null}""", HttpStatusCode.Conflict)
                        else -> {
                            val fixtureOrderId = when {
                                authorization != "Bearer mock-access-token" || eventId >= 500 || items.any { it.first >= 3000 } -> nextCreatedOrderId++
                                buyerName.equals("decline", ignoreCase = true) -> 7003
                                buyerName.equals("expired", ignoreCase = true) -> 7004
                                buyerName.equals("unknown", ignoreCase = true) -> 7005
                                items.all { (id, _) -> id == 1002 || id == 2001 } -> 7001
                                else -> 7002
                            }
                            val orderId=if(mockOrders.containsKey(fixtureOrderId)) nextCreatedOrderId++ else fixtureOrderId
                            val orderJson = mockOrderResponse(
                                orderId = orderId,
                                eventId = path.removePrefix("/events/").substringBefore('/').toInt(),
                                buyerName = buyerName,
                                items = items,
                                expired = false,
                                ticketPrices = ticketPrices,
                                buyerEmail = buyerEmail(authorization),
                            )
                            changeInventory(Json.parseToJsonElement(orderJson).jsonObject,true)
                            mockOrders[orderId] = orderJson
                            orderOwners[orderId] = requireNotNull(authorization)
                            respondJson(orderJson, HttpStatusCode.Created)
                        }
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/orders/\\d+/pay")) -> {
                if (!isBuyer(authorizationHeader)) {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val orderId = path.removePrefix("/orders/").substringBefore('/').toInt()
                    val orderJson = mockOrders[orderId].takeIf { orderOwners[orderId] == authorizationHeader }
                    val body = (request.body as? OutgoingContent.ByteArrayContent)
                        ?.bytes()?.decodeToString().orEmpty()
                    val validEmptyObject = runCatching {
                        Json.parseToJsonElement(body).jsonObject.isEmpty()
                    }.getOrDefault(false)
                    when {
                        !validEmptyObject -> respondJson(MOCK_ORDER_VALIDATION_JSON, HttpStatusCode.UnprocessableEntity)
                        orderJson == null -> respondJson(MOCK_ORDER_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                        Json.parseToJsonElement(orderJson).jsonObject["status"]?.jsonPrimitive?.content!="pending" -> respondJson("""{"error":"OrderNotPayableError","message":"Order no longer pending"}""",HttpStatusCode.Conflict)
                        eventById(Json.parseToJsonElement(orderJson).jsonObject.getValue("event_id").jsonPrimitive.int)?.get("suspended_at")?.let {it!=JsonNull}==true -> respondJson("""{"error":"OrderNotPayableError","message":"Event suspended"}""",HttpStatusCode.Conflict)
                        orderId == 7004 -> respondJson(MOCK_ORDER_EXPIRED_JSON, HttpStatusCode.Conflict)
                        orderId == 7003 && paymentAttempts.getOrElse(orderId) { 0 } == 0 -> {
                            paymentAttempts[orderId] = 1
                            respondJson(MOCK_PAYMENT_DECLINED_JSON, HttpStatusCode.PaymentRequired)
                        }
                        orderId == 7005 && paymentAttempts.getOrElse(orderId) { 0 } == 0 -> {
                            paymentAttempts[orderId] = 1
                            mockOrders[orderId] = mockPaidOrderResponse(orderJson)
                            throw IOException("Mock connection interrupted after payment was processed")
                        }
                        else -> {
                            paymentAttempts[orderId] = paymentAttempts.getOrElse(orderId) { 0 } + 1
                            val paid = mockPaidOrderResponse(orderJson)
                            mockOrders[orderId] = paid
                            respondJson(paid, HttpStatusCode.OK)
                        }
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/orders/\\d+/cancel")) -> {
                if (!isBuyer(authorizationHeader)) {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val orderId = path.removePrefix("/orders/").substringBefore('/').toInt()
                    val orderJson = mockOrders[orderId].takeIf { orderOwners[orderId] == authorizationHeader }
                    val body = (request.body as? OutgoingContent.ByteArrayContent)
                        ?.bytes()?.decodeToString().orEmpty()
                    val validEmptyObject = runCatching {
                        Json.parseToJsonElement(body).jsonObject.isEmpty()
                    }.getOrDefault(false)
                    if (!validEmptyObject) {
                        respondJson(MOCK_ORDER_VALIDATION_JSON, HttpStatusCode.UnprocessableEntity)
                    } else if (orderJson == null) {
                        respondJson(MOCK_ORDER_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                    } else {
                        val order = Json.parseToJsonElement(orderJson).jsonObject
                        val status = order["status"]?.jsonPrimitive?.content
                        val total = order["total_amount"]?.jsonPrimitive?.int ?: 0
                        val cancellable = status == "pending" || (status == "confirmed" && total == 0)
                        when {
                            !cancellable -> respondJson(MOCK_ORDER_NOT_CANCELLABLE_JSON, HttpStatusCode.Conflict)
                            orderId == 7103 && cancellationAttempts.getOrElse(orderId) { 0 } == 0 -> {
                                cancellationAttempts[orderId] = 1
                                changeInventory(order,false)
                                mockOrders[orderId] = mockCancelledOrderResponse(orderJson)
                                throw IOException("Mock connection interrupted after cancellation was processed")
                            }
                            else -> {
                                cancellationAttempts[orderId] = cancellationAttempts.getOrElse(orderId) { 0 } + 1
                                val cancelled = mockCancelledOrderResponse(orderJson)
                                changeInventory(order,false)
                                mockOrders[orderId] = cancelled
                                respondJson(cancelled, HttpStatusCode.OK)
                            }
                        }
                    }
                }
            }
            request.method == HttpMethod.Get && path == "/orders/me" -> {
                when {
                    authorizationHeader == "Bearer mock-history-offline-token" -> throw IOException("Mock order history unavailable")
                    isBuyer(authorizationHeader) -> respondJson(
                        buildJsonArray {
                            mockOrders.filterKeys { orderOwners[it] == authorizationHeader }.values.forEach { add(Json.parseToJsonElement(it)) }
                        }.toString(),
                        HttpStatusCode.OK,
                    )
                    else -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/orders/\\d+")) -> {
                if (!isBuyer(authorizationHeader)) {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val orderId = path.substringAfterLast('/').toInt()
                    mockOrders[orderId]?.takeIf { orderOwners[orderId] == authorizationHeader }?.let { respondJson(it, HttpStatusCode.OK) }
                        ?: respondJson(MOCK_ORDER_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/tickets/\\d+/qr")) -> {
                if (!isBuyer(authorizationHeader)) {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val ticketId = path.removePrefix("/tickets/").substringBefore('/').toInt()
                    val ticket = mockOrders.filterKeys { orderOwners[it] == authorizationHeader }.values.flatMap { orderJson ->
                        Json.parseToJsonElement(orderJson).jsonObject["tickets"]?.jsonArray.orEmpty()
                    }.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.int == ticketId }?.jsonObject
                    when {
                        ticket == null -> respondJson(MOCK_TICKET_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                        ticketId % 10 == 1 && qrAttempts.getOrElse(ticketId) { 0 } == 0 -> {
                            qrAttempts[ticketId] = 1
                            throw IOException("Mock QR connection interrupted")
                        }
                        else -> respondPng(mockTicketQrPng(ticket.getValue("code").jsonPrimitive.content))
                    }
                }
            }
            request.method == HttpMethod.Get && (path == "/events" || path == "/events/categories") -> {
                val all=catalogPublic()

                val upcoming=all.filter { Instant.parse(it.getValue("end_time").jsonPrimitive.content) >= now }
                if(path.endsWith("/categories")) respondJson(JsonArray(all.filter {Instant.parse(it.getValue("end_time").jsonPrimitive.content)>now}.mapNotNull { (it["category"] as? JsonPrimitive)?.contentOrNull }.distinct().sorted().map(::JsonPrimitive)).toString(),HttpStatusCode.OK)
                else {
                    val p=request.url.parameters
                    val limit=p["limit"]?.toIntOrNull() ?: if(p["limit"]==null) 20 else 0
                    val offset=p["offset"]?.toIntOrNull() ?: if(p["offset"]==null) 0 else -1
                    val q=p["q"].orEmpty().trim(); val category=p["category"]?.trim()?.takeIf {it.isNotEmpty()}
                    val from=p["date_from"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val to=p["date_to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if(limit !in 1..100 || offset < 0 || q.length > 100 || category.orEmpty().length > 100 || p["date_from"] != null && from == null || p["date_to"] != null && to == null)
                        respondJson("""{"error":"VALIDATION_ERROR","message":"Invalid catalog parameters","fields":{"query":"Invalid value"}}""",HttpStatusCode.UnprocessableEntity)
                    else {
                        val filtered=(if(p["include_past"] == "true") all else upcoming).filter { event ->
                            (q.isEmpty() || listOf("title","description","venue_name","venue_address").any { (event[it] as? JsonPrimitive)?.contentOrNull?.contains(q,ignoreCase=true) == true }) &&
                            (category == null || (event["category"] as? JsonPrimitive)?.contentOrNull?.equals(category,ignoreCase=true) == true) &&
                            (from == null || Instant.parse(event.getValue("start_time").jsonPrimitive.content) >= from) &&
                            (to == null || Instant.parse(event.getValue("start_time").jsonPrimitive.content) < to)
                        }.sortedWith(compareBy<JsonObject> { Instant.parse(it.getValue("start_time").jsonPrimitive.content) }.thenBy { it.getValue("id").jsonPrimitive.int })
                        respondJson(buildJsonObject { put("items",JsonArray(filtered.drop(offset).take(limit))); put("total",filtered.size) }.toString(),HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/ticket-types")) -> {
                val id=path.split('/')[2].toInt()
                if(!publicPage(eventById(id))) respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                else if(id==104 && event104TicketAttempts++==0) respondJson(MOCK_TICKETS_ERROR_JSON,HttpStatusCode.InternalServerError)
                else respondJson(JsonArray(eventTypes(id).filter {it["is_hidden"]?.jsonPrimitive?.content!="true"}).toString(),HttpStatusCode.OK)
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/manage")) -> {
                val owner=authorizationHeader.orEmpty();val id=path.split('/')[2].toInt()
                val profile=organizerProfiles[owner]?.let {Json.parseToJsonElement(it).jsonObject}
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    profile==null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found"}""",HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content!="approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Not approved"}""",HttpStatusCode.Forbidden)
                    eventById(id)==null -> respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    ownedEvents(owner).none {it["id"]?.jsonPrimitive?.int==id} -> respondJson("""{"error":"EventAccessDeniedError","message":"Not owner"}""",HttpStatusCode.Forbidden)
                    else -> respondJson(requireNotNull(eventById(id)).toString(),HttpStatusCode.OK)
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+")) -> {
                val event=eventById(path.substringAfterLast('/').toInt())
                if(publicPage(event)) respondJson(requireNotNull(event).toString(),HttpStatusCode.OK)
                else respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
            }
            else -> error("Unexpected mock request: ${request.method.value} ${request.url}")
        }
    }) {
        installSessionErrorValidation()
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

private val mockEvents by lazy { Json.parseToJsonElement(MOCK_EVENTS_JSON).jsonArray }

private fun mockOrderResponse(
    orderId: Int,
    eventId: Int,
    buyerName: String,
    items: List<Pair<Int, Int>>,
    expired: Boolean = false,
    createdAt: String = "2026-11-13T12:00:00+05:00",
    ticketPrices: Map<Int, Int> = emptyMap(),
    buyerEmail: String = "visitor@biletflow.kz",
): String {
    val prices = mapOf(1001 to 5000, 1002 to 0, 2001 to 0, 2002 to 1000) + ticketPrices
    val total = items.sumOf { (ticketTypeId, quantity) ->
        (prices[ticketTypeId] ?: 0).toLong() * quantity.toLong()
    }
    val confirmed = total == 0L
    return buildJsonObject {
        put("id", orderId)
        put("event_id", eventId)
        put("status", if (confirmed) "confirmed" else "pending")
        put("total_amount", total)
        put("buyer_name", buyerName)
        put("buyer_email", buyerEmail)
        put("created_at", createdAt)
        when {
            confirmed -> put("expires_at", JsonNull)
            expired -> put("expires_at", "2020-01-01T00:00:00+05:00")
            else -> put("expires_at", "2027-12-31T23:59:00+05:00")
        }
        put("paid_at", JsonNull)
        put("cancelled_at", JsonNull)
        put("refunded_at", JsonNull)
        putJsonArray("items") {
            items.forEachIndexed { index, (ticketTypeId, quantity) ->
                add(buildJsonObject {
                    put("id", 8000 + index)
                    put("ticket_type_id", ticketTypeId)
                    put("quantity", quantity)
                    put("unit_price", prices[ticketTypeId] ?: 0)
                })
            }
        }
        putJsonArray("tickets") {
            if (confirmed) {
                var ticketIndex = 0
                items.forEach { (ticketTypeId, quantity) ->
                    repeat(quantity) { index ->
                        add(buildJsonObject {
                            put("id", orderId * 10 + ticketIndex++)
                            put("code", "MOCK-$orderId-$ticketTypeId-$index")
                            put("ticket_type_id", ticketTypeId)
                            put("event_id", eventId)
                            put("attendee_name", buyerName)
                            put("status", "valid")
                            put("created_at", "2026-11-13T12:00:00+05:00")
                            put("checked_in_at", JsonNull)
                        })
                    }
                }
            }
        }
    }.toString()
}

private fun mockPaidOrderResponse(orderJson: String): String {
    val original = Json.parseToJsonElement(orderJson).jsonObject
    val orderId = original["id"]?.jsonPrimitive?.int ?: 0
    return buildJsonObject {
        original.forEach { (key, value) -> put(key, value) }
        put("status", "confirmed")
        put("expires_at", JsonNull)
        put("paid_at", "2026-11-13T12:05:00+05:00")
        putJsonArray("tickets") {
            var ticketIndex = 0
            original["items"]?.jsonArray.orEmpty().forEach { itemElement ->
                val item = itemElement.jsonObject
                val ticketTypeId = item["ticket_type_id"]?.jsonPrimitive?.int ?: return@forEach
                val quantity = item["quantity"]?.jsonPrimitive?.int ?: return@forEach
                repeat(quantity) {
                    add(buildJsonObject {
                        put("id", orderId * 10 + ticketIndex)
                        put("code", "MOCK-PAID-${orderId * 10 + ticketIndex}")
                        put("ticket_type_id", ticketTypeId)
                        put("event_id", original["event_id"]?.jsonPrimitive?.int ?: 0)
                        put("attendee_name", original["buyer_name"]?.jsonPrimitive?.content.orEmpty())
                        put("status", "valid")
                        put("created_at", "2026-11-13T12:05:00+05:00")
                        put("checked_in_at", JsonNull)
                    })
                    ticketIndex++
                }
            }
        }
    }.toString()
}

private fun mockCancelledOrderResponse(orderJson: String): String {
    val original = Json.parseToJsonElement(orderJson).jsonObject
    return buildJsonObject {
        original.forEach { (key, value) -> put(key, value) }
        put("status", "cancelled")
        put("expires_at", JsonNull)
        put("cancelled_at", "2026-11-14T10:00:00+05:00")
        putJsonArray("tickets") {
            original["tickets"]?.jsonArray.orEmpty().forEach { ticket ->
                add(buildJsonObject {
                    ticket.jsonObject.forEach { (key, value) -> put(key, value) }
                    put("status", "cancelled")
                })
            }
        }
    }.toString()
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondPng(content: ByteArray) = respond(
    content = content,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "image/png"),
)

private const val MOCK_EVENT_NOT_FOUND_JSON = """
{"error":"EventNotFoundError","message":"Event Not Found","fields":null}
"""

private const val MOCK_LOGIN_SUCCESS_JSON = """
{"access_token":"mock-access-token","refresh_token":"mock-refresh-token","token_type":"bearer"}
"""

private const val MOCK_EMPTY_LOGIN_SUCCESS_JSON = """
{"access_token":"mock-empty-access-token","refresh_token":"mock-empty-refresh-token","token_type":"bearer"}
"""

private const val MOCK_HISTORY_OFFLINE_LOGIN_SUCCESS_JSON = """
{"access_token":"mock-history-offline-token","refresh_token":"mock-history-offline-refresh-token","token_type":"bearer"}
"""

private const val MOCK_INVALID_CREDENTIALS_JSON = """
{"error":"InvalidCredentialsError","message":"Invalid Credentials","fields":null}
"""

private const val MOCK_TICKETS_ERROR_JSON = """
{"error":"INTERNAL_SERVER_ERROR","message":"Ticket types are temporarily unavailable","fields":null}
"""

private const val MOCK_ORDER_UNAUTHORIZED_JSON = """
{"error":"InvalidCredentialsError","message":"Invalid access token","fields":null}
"""

private const val MOCK_ORDER_CONFLICT_JSON = """
{"error":"TicketTypeSoldOutError","message":"Selected ticket type is sold out","fields":null}
"""

private const val MOCK_ORDER_VALIDATION_JSON = """
{"error":"VALIDATION_ERROR","message":"Invalid order payload","fields":null}
"""

private const val MOCK_PAYMENT_DECLINED_JSON = """
{"error":"PaymentFailedError","message":"Payment was declined","fields":null}
"""

private const val MOCK_ORDER_EXPIRED_JSON = """
{"error":"OrderExpiredError","message":"Order has expired","fields":null}
"""

private const val MOCK_ORDER_NOT_CANCELLABLE_JSON = """
{"error":"OrderNotCancellableError","message":"Order cannot be cancelled","fields":null}
"""

private const val MOCK_ORDER_NOT_FOUND_JSON = """
{"error":"OrderNotFoundError","message":"Order not found","fields":null}
"""

private const val MOCK_TICKET_NOT_FOUND_JSON = """
{"error":"TicketNotFoundError","message":"Ticket not found","fields":null}
"""

private const val MOCK_EVENT_101_TICKET_TYPES_JSON = """
[
  {
    "id": 1001,
    "event_id": 101,
    "name": "Стандарт",
    "description": "Вход в основную зону концерта.",
    "price": 5000,
    "quantity": 200,
    "sales_start": "2026-11-01T09:00:00+05:00",
    "sales_end": "2026-12-05T18:30:00+05:00",
    "is_hidden": false,
    "max_per_order": 6,
    "sold_count": 198
  },
  {
    "id": 1002,
    "event_id": 101,
    "name": "Волонтёрский",
    "description": "Бесплатный билет по предварительной регистрации.",
    "price": 0,
    "quantity": 30,
    "sales_start": "2026-11-01T09:00:00+05:00",
    "sales_end": "2026-12-04T23:59:00+05:00",
    "is_hidden": false,
    "max_per_order": 1,
    "sold_count": 18
  },
  {
    "id": 1003,
    "event_id": 101,
    "name": "Ранний вход",
    "description": "Продажи этого типа уже завершены.",
    "price": 3000,
    "quantity": 50,
    "sales_start": "2026-10-01T09:00:00+05:00",
    "sales_end": "2026-11-10T23:59:00+05:00",
    "is_hidden": false,
    "max_per_order": 4,
    "sold_count": 20
  }
]
"""

private const val MOCK_EVENT_102_TICKET_TYPES_JSON = """
[
  {
    "id": 2001,
    "event_id": 102,
    "name": "Регистрация на лекцию",
    "description": null,
    "price": 0,
    "quantity": 200,
    "sales_start": "2026-11-20T10:00:00+05:00",
    "sales_end": "2026-12-12T18:00:00+05:00",
    "is_hidden": false,
    "max_per_order": 2,
    "sold_count": 200
  },
  {
    "id": 2002,
    "event_id": 102,
    "name": "Поздняя регистрация",
    "description": "Продажи откроются позднее.",
    "price": 1000,
    "quantity": 50,
    "sales_start": "2026-11-20T10:00:00+05:00",
    "sales_end": "2026-12-12T18:00:00+05:00",
    "is_hidden": false,
    "max_per_order": 3,
    "sold_count": 0
  }
]
"""

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
