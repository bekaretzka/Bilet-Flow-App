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

// The app enables a ready-to-buy concert; activation tests keep the incomplete checklist fixtures.
fun createMockEventsHttpClient(visitorCheckoutReady: Boolean = false): HttpClient {
    val organizerProfiles = mutableMapOf<String, String>()
    val createdTicketTypes = mutableMapOf<String, MutableList<JsonObject>>()
    var nextTicketTypeId = 3000
    val createdEvents = mutableMapOf<String, MutableList<JsonObject>>()
    var nextEventId = 500
    val organizerReads = mutableMapOf<String, Int>()
    val myEventReads = mutableMapOf<String, Int>()
    val paidSalesReads = mutableMapOf<Int, Int>()
    val consumedRefreshTokens = mutableSetOf<String>()
    val accessAliases = mutableMapOf<String, String>()
    val refreshOwners = mutableMapOf(
        "mock-refresh-token" to "Bearer mock-access-token",
        "mock-empty-refresh-token" to "Bearer mock-empty-access-token",
        "mock-history-offline-refresh-token" to "Bearer mock-history-offline-token",
    )
    var rotation = 0
    val registeredUsers = mutableMapOf<String, String>()
    fun isBuyer(owner: String?): Boolean = owner in refreshOwners.values ||
        owner?.removePrefix("Bearer mock-user-")?.toIntOrNull()?.let { it in registeredUsers.values.indices } == true
    fun buyerEmail(owner: String?): String = when (owner) {
        "Bearer mock-access-token" -> "visitor@biletflow.kz"
        "Bearer mock-empty-access-token" -> "empty@biletflow.kz"
        "Bearer mock-history-offline-token" -> "history-offline@biletflow.kz"
        else -> registeredUsers.keys.elementAt(owner!!.removePrefix("Bearer mock-user-").toInt())
    }
    val orderOwners = mutableMapOf(7101 to "Bearer mock-access-token", 7102 to "Bearer mock-access-token", 7103 to "Bearer mock-access-token")
    val supportEntries = mutableMapOf<Pair<String, String>, Pair<JsonObject, String>>()
    val supportMutex = Mutex()
    var nextSupportId = 9000
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
    fun ownedEvents(owner: String): List<JsonObject> {
        val created = createdEvents[owner].orEmpty()
        val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
        if (owner != "Bearer mock-access-token" || profile == null) return created
        val seeded = listOf("draft" to "private", "published" to "public", "cancelled" to "unlisted").mapIndexedNotNull { index, pair ->
            val base = mockEvents[index].jsonObject
            if (created.any { it["id"] == base["id"] }) null
            else buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("organizer_id", profile.getValue("id")); put("status", pair.first); put("visibility", pair.second)
            }
        }
        return created + seeded
    }
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
        createdAt = "2026-11-12T18:00:00+05:00",
    )
    mockOrders[7102] = mockOrderResponse(
        orderId = 7102,
        eventId = 101,
        buyerName = "Mock Visitor",
        items = listOf(1002 to 1),
        createdAt = "2026-11-11T12:00:00+05:00",
    )
    mockOrders[7103] = mockOrderResponse(
        orderId = 7103,
        eventId = 102,
        buyerName = "Cancel Unknown",
        items = listOf(2002 to 1),
        createdAt = "2026-11-13T10:00:00+05:00",
    )
    return HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val rawAuthorization = request.headers[HttpHeaders.Authorization]
        val authorizationHeader = accessAliases[rawAuthorization] ?: rawAuthorization
        when {
            request.method == HttpMethod.Get && (path == "/support/requests/me" || path.matches(Regex("/support/requests/\\d+(?:/messages)?"))) -> supportMutex.withLock {
                val owner = authorizationHeader.orEmpty()
                val id = path.split('/').getOrNull(3)?.toIntOrNull()
                val messages = path.endsWith("/messages")
                val records = supportEntries.filterKeys { it.first == owner }.values.map { Json.parseToJsonElement(it.second).jsonObject }
                val found = records.firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val rawLimit = request.url.parameters["limit"]
                val limit = rawLimit?.toIntOrNull() ?: if(rawLimit == null) 50 else 0
                val cursor = request.url.parameters["cursor"]
                val prefix = if(messages) "support-$id-before-" else "support-before-"
                val before = cursor?.removePrefix(prefix)?.toIntOrNull()?.takeIf { cursor.startsWith(prefix) }
                fun enrich(record: JsonObject) = buildJsonObject {
                    record.forEach { (key,value) -> put(key,value) }
                    put("updated_at",record.getValue("created_at"))
                    val eventId = record.getValue("event_id").jsonPrimitive.int
                    val event = createdEvents.values.flatten().firstOrNull { it["id"]?.jsonPrimitive?.int == eventId }
                        ?: mockEvents.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.int == eventId }?.jsonObject
                    put("event_title",event?.get("title") ?: JsonNull)
                }
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    id != null && found == null -> respondJson("""{"error":"SupportRequestNotFoundError","message":"Request not found"}""",HttpStatusCode.NotFound)
                    limit !in 1..100 || cursor != null && (before == null || before <= 0) -> respondJson("""{"error":"VALIDATION_ERROR","message":"Invalid cursor or limit"}""",HttpStatusCode.UnprocessableEntity)
                    id != null && !messages -> respondJson(enrich(requireNotNull(found)).toString(),HttpStatusCode.OK)
                    else -> {
                        val source = if(messages) listOf(buildJsonObject {
                            val record = requireNotNull(found)
                            put("id",record.getValue("id")); put("request_id",record.getValue("id"))
                            put("author_name",JsonNull); put("author_role","visitor")
                            put("text",record.getValue("message")); put("created_at",record.getValue("created_at"))
                        }) else records.map(::enrich)
                        val remaining = source.sortedByDescending { it.getValue("id").jsonPrimitive.int }
                            .filter { before == null || it.getValue("id").jsonPrimitive.int < before }
                        val page = remaining.take(limit)
                        respondJson(buildJsonObject {
                            put("items",JsonArray(page))
                            put("next_cursor",if(remaining.size > limit) JsonPrimitive(prefix + page.last().getValue("id").jsonPrimitive.int) else JsonNull)
                        }.toString(),HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Post && path == "/support/requests" -> supportMutex.withLock {
                val owner = authorizationHeader.orEmpty()
                val input = runCatching { Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject }.getOrNull()
                fun value(key: String) = (input?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
                fun number(key: String) = (input?.get(key) as? JsonPrimitive)?.intOrNull
                val eventId = number("event_id")
                val orderId = number("order_id")
                val ticketId = number("ticket_id")
                val category = value("category"); val subject = value("subject").trim(); val message = value("message").trim()
                val requestId = value("client_request_id")
                fun length(text: String) = text.indices.count { it == 0 || !text[it].isLowSurrogate() || !text[it-1].isHighSurrogate() }
                val errors = buildList {
                    if (eventId == null || eventId <= 0) add("event_id")
                    listOf("order_id", "ticket_id").forEach { key ->
                        if(input?.get(key) != null && input[key] != JsonNull && (number(key) ?: 0) <= 0) add(key)
                    }
                    if(category !in listOf("event_question", "ticket_issue", "payment_issue", "other")) add("category")
                    if(length(subject) !in 3..200) add("subject")
                    if(length(message) !in 1..5000) add("message")
                    if(!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}").matches(requestId)) add("client_request_id")
                }
                val canonical = buildJsonObject {
                    put("event_id", eventId?.let(::JsonPrimitive) ?: JsonNull)
                    put("order_id", orderId?.let(::JsonPrimitive) ?: JsonNull); put("ticket_id", ticketId?.let(::JsonPrimitive) ?: JsonNull)
                    put("category",category); put("subject",subject); put("message",message); put("client_request_id",requestId)
                }
                val saved = supportEntries[owner to requestId]
                val event = createdEvents.values.flatten().firstOrNull { it["id"]?.jsonPrimitive?.int == eventId }
                    ?: mockEvents.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.int == eventId }?.jsonObject
                val order = orderId?.let { mockOrders[it] }?.let { Json.parseToJsonElement(it).jsonObject }
                val ticketOrder = ticketId?.let { id -> mockOrders.entries.firstOrNull { (_,json) ->
                    Json.parseToJsonElement(json).jsonObject["tickets"]?.jsonArray.orEmpty().any { it.jsonObject["id"]?.jsonPrimitive?.int == id }
                } }
                val ticketOrderJson = ticketOrder?.value?.let { Json.parseToJsonElement(it).jsonObject }
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON,HttpStatusCode.Unauthorized)
                    errors.isNotEmpty() -> respondJson(buildJsonObject {
                        put("error","VALIDATION_ERROR"); put("message","Validation failed")
                        put("fields",buildJsonObject { errors.forEach { put(it,"Invalid value") } })
                    }.toString(),HttpStatusCode.UnprocessableEntity)
                    saved != null && saved.first != canonical -> respondJson("""{"error":"ClientRequestConflictError","message":"Request ID already used with another body","fields":{"client_request_id":"Conflict"}}""",HttpStatusCode.Conflict)
                    saved != null -> respondJson(saved.second,HttpStatusCode.Created)
                    event == null || orderId == null && ticketId == null && (event["status"]?.jsonPrimitive?.content != "published" || event["visibility"]?.jsonPrimitive?.content == "private") -> respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    orderId != null && (order == null || orderOwners[orderId] != owner || order["event_id"]?.jsonPrimitive?.int != eventId) -> respondJson(MOCK_ORDER_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    ticketId != null && (ticketOrder == null || orderOwners[ticketOrder.key] != owner || ticketOrderJson?.get("event_id")?.jsonPrimitive?.int != eventId || orderId != null && ticketOrder.key != orderId) -> respondJson(MOCK_TICKET_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                    subject == "support-offline" -> throw IOException("Mock support unavailable")
                    else -> {
                        val result = buildJsonObject {
                            canonical.forEach { (key,value) -> put(key,value) }
                            put("order_id", (orderId ?: ticketOrder?.key)?.let(::JsonPrimitive) ?: JsonNull)
                            put("id",nextSupportId++); put("status","open"); put("created_at","2026-11-24T14:00:00+05:00")
                        }.toString()
                        supportEntries[owner to requestId] = canonical to result
                        if(subject == "support-unknown") throw IOException("Mock support response lost after creation")
                        respondJson(result,HttpStatusCode.Created)
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/organizer/events/\\d+/orders(?:/\\d+)?")) -> {
                val owner = authorizationHeader.orEmpty()
                val parts = path.split('/')
                val eventId = parts[3].toInt()
                val orderId = parts.getOrNull(5)?.toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val exists = mockEvents.any { it.jsonObject["id"]?.jsonPrimitive?.int == eventId } ||
                    createdEvents.values.flatten().any { it["id"]?.jsonPrimitive?.int == eventId }
                val limit = request.url.parameters["limit"]?.toIntOrNull() ?: if (request.url.parameters["limit"] == null) 50 else 0
                val status = request.url.parameters["status"]
                val cursor = request.url.parameters["cursor"]
                val prefix = "orders-$eventId-${status ?: "all"}-after-"
                val afterId = cursor?.removePrefix(prefix)?.toIntOrNull()?.takeIf { cursor.startsWith(prefix) }
                val all = mockOrders.values.map { Json.parseToJsonElement(it).jsonObject }
                    .filter { it["event_id"]?.jsonPrimitive?.int == eventId }
                    .sortedWith(compareByDescending<JsonObject> { Instant.parse(it.getValue("created_at").jsonPrimitive.content) }
                        .thenByDescending { it.getValue("id").jsonPrimitive.int })
                val anchor = all.firstOrNull { it["id"]?.jsonPrimitive?.int == afterId }
                fun projection(order: JsonObject) = buildJsonObject {
                    listOf("id", "event_id", "status", "total_amount", "buyer_name", "buyer_email", "created_at", "paid_at", "refunded_at")
                        .forEach { put(it, order[it] ?: JsonNull) }
                }
                when {
                    !isBuyer(owner) -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                    profile == null -> respondJson("""{"error":"OrganizerNotFoundError","message":"Organizer not found","fields":null}""", HttpStatusCode.NotFound)
                    profile["status"]?.jsonPrimitive?.content != "approved" -> respondJson("""{"error":"OrganizerNotApprovedError","message":"Not approved","fields":null}""", HttpStatusCode.Forbidden)
                    !exists -> respondJson(MOCK_EVENT_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                    ownedEvents(owner).none { it["id"]?.jsonPrimitive?.int == eventId } -> respondJson("""{"error":"EventAccessDeniedError","message":"Not the event owner","fields":null}""", HttpStatusCode.Forbidden)
                    limit !in 1..100 || status != null && status !in listOf("pending", "confirmed", "cancelled", "refunded", "expired") || cursor != null && anchor == null ->
                        respondJson("""{"error":"VALIDATION_ERROR","message":"Invalid pagination or status","fields":{"cursor":"Invalid parameters"}}""", HttpStatusCode.UnprocessableEntity)
                    orderId != null -> all.firstOrNull { it["id"]?.jsonPrimitive?.int == orderId }?.let { respondJson(projection(it).toString(), HttpStatusCode.OK) }
                        ?: respondJson(MOCK_ORDER_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                    else -> {
                        val remaining = all.drop(if (anchor == null) 0 else all.indexOf(anchor) + 1)
                            .filter { status == null || it["status"]?.jsonPrimitive?.content == status }
                        val page = remaining.take(limit)
                        respondJson(buildJsonObject {
                            put("items", buildJsonArray { page.forEach { add(projection(it)) } })
                            put("next_cursor", if (remaining.size > limit) JsonPrimitive(prefix + page.last().getValue("id").jsonPrimitive.int) else JsonNull)
                        }.toString(), HttpStatusCode.OK)
                    }
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/organizer/events/\\d+/ticket-types(?:/\\d+)?")) -> {
                val owner = authorizationHeader.orEmpty()
                val parts = path.split('/')
                val eventId = parts[3].toInt()
                val ticketId = parts.getOrNull(5)?.toInt()
                val profile = organizerProfiles[owner]?.let { Json.parseToJsonElement(it).jsonObject }
                val exists = mockEvents.any { it.jsonObject["id"]?.jsonPrimitive?.int == eventId } ||
                    createdEvents.values.flatten().any { it["id"]?.jsonPrimitive?.int == eventId }
                val limit = request.url.parameters["limit"]?.toIntOrNull() ?: if(request.url.parameters["limit"] == null) 50 else 0
                val cursor = request.url.parameters["cursor"]
                val after = if (cursor == null) 0 else cursor.removePrefix("types-$eventId-after-").toIntOrNull()
                    ?.takeIf { cursor.startsWith("types-$eventId-after-") && it >= 0 }
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
                    eventId <= 0 || ticketId != null && ticketId <= 0 || limit !in 1..100 || after == null -> respondJson("""{"error":"VALIDATION_ERROR","message":"Invalid pagination parameters","fields":{"cursor":"Invalid cursor or limit"}}""", HttpStatusCode.UnprocessableEntity)
                    ticketId != null -> types.firstOrNull { it["id"]?.jsonPrimitive?.int == ticketId }?.let { respondJson(it.toString(), HttpStatusCode.OK) }
                        ?: respondJson("""{"error":"TicketTypeNotFoundError","message":"Ticket type not found for this event","fields":null}""", HttpStatusCode.NotFound)
                    else -> {
                        val remaining = types.filter { it.getValue("id").jsonPrimitive.int > after }
                        val page = remaining.take(limit)
                        respondJson(buildJsonObject {
                            put("items", buildJsonArray { page.forEach { add(it) } })
                            put("next_cursor", if(remaining.size > limit) JsonPrimitive("types-$eventId-after-${page.last().getValue("id").jsonPrimitive.int}") else JsonNull)
                        }.toString(), HttpStatusCode.OK)
                    }
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
                                    putJsonArray("tickets") { order["tickets"]?.jsonArray.orEmpty().forEach { ticket ->
                                        add(buildJsonObject { ticket.jsonObject.forEach { (k, v) -> put(k, v) }; put("status", "refunded") })
                                    } }
                                }
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
                    publish && (original["venue_name"]?.jsonPrimitive?.content.isNullOrBlank() || original["venue_address"]?.jsonPrimitive?.content.isNullOrBlank()) -> respondJson("""{"error":"EventNotReadyToPublishError","message":"Venue and address are required","fields":{"venue_name":"required","venue_address":"required"}}""",HttpStatusCode.BadRequest)
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
                                val refunded = order["paid_at"] != null && order["paid_at"] != JsonNull
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
                        val profile = """{"id":${31 + organizerProfiles.size},"user_id":$userId,"status":"pending"}"""
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
                    email in registeredUsers || email in setOf("visitor@biletflow.kz", "empty@biletflow.kz", "history-offline@biletflow.kz") ->
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
                    email != null && registeredUsers[email] == password -> respondJson(
                        buildJsonObject {
                            put("access_token", "mock-user-${registeredUsers.keys.indexOf(email)}")
                            put("refresh_token", "mock-refresh-${registeredUsers.keys.indexOf(email)}")
                            put("token_type", "bearer")
                        }.toString(), HttpStatusCode.OK,
                    )
                    email == "offline@biletflow.kz" -> throw IOException("Mock network unavailable")
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
                    when {
                        buyerName.equals("offline", ignoreCase = true) ->
                            throw IOException("Mock connection interrupted after sending order")
                        buyerName.equals("conflict", ignoreCase = true) ->
                            respondJson(MOCK_ORDER_CONFLICT_JSON, HttpStatusCode.Conflict)
                        buyerName.isBlank() || items.isEmpty() ->
                            respondJson(MOCK_ORDER_VALIDATION_JSON, HttpStatusCode.UnprocessableEntity)
                        items.any { (id, _) -> (ticketPrices[id] ?: 0) > 0 } && paidStatus(eventOwner, eventId)["status"]?.jsonPrimitive?.content != "active" ->
                            respondJson("""{"error":"PaidSalesNotActiveError","message":"Paid sales are not active","fields":null}""", HttpStatusCode.Conflict)
                        else -> {
                            val orderId = when {
                                authorization != "Bearer mock-access-token" || eventId >= 500 || items.any { it.first >= 3000 } -> nextCreatedOrderId++
                                buyerName.equals("decline", ignoreCase = true) -> 7003
                                buyerName.equals("expired", ignoreCase = true) -> 7004
                                buyerName.equals("unknown", ignoreCase = true) -> 7005
                                items.all { (id, _) -> id == 1002 || id == 2001 } -> 7001
                                else -> 7002
                            }
                            val orderJson = mockOrderResponse(
                                orderId = orderId,
                                eventId = path.removePrefix("/events/").substringBefore('/').toInt(),
                                buyerName = buyerName,
                                items = items,
                                expired = false,
                                ticketPrices = ticketPrices,
                                buyerEmail = buyerEmail(authorization),
                            )
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
                                mockOrders[orderId] = mockCancelledOrderResponse(orderJson)
                                throw IOException("Mock connection interrupted after cancellation was processed")
                            }
                            else -> {
                                cancellationAttempts[orderId] = cancellationAttempts.getOrElse(orderId) { 0 } + 1
                                val cancelled = mockCancelledOrderResponse(orderJson)
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
            request.method == HttpMethod.Get && path == "/events" ->
                respondJson(buildJsonArray {
                    val changed = createdEvents.values.flatten()
                    mockEvents.filter { base -> changed.none { it["id"] == base.jsonObject["id"] } }.forEach { add(it) }
                    changed.filter { it["status"]?.jsonPrimitive?.content == "published" && it["visibility"]?.jsonPrimitive?.content == "public" }.forEach { add(it) }
                }.toString(), HttpStatusCode.OK)
            request.method == HttpMethod.Get && path.matches(Regex("/events/\\d+/ticket-types")) -> {
                val id = path.removePrefix("/events/").substringBefore('/').toInt()
                val ownEvent = createdEvents.values.flatten().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                val extra = createdTicketTypes.values.flatten().filter { it["event_id"]?.jsonPrimitive?.int == id }
                if (ownEvent != null && ownEvent["status"]?.jsonPrimitive?.content != "published") {
                    respondJson(MOCK_EVENT_NOT_FOUND_JSON,HttpStatusCode.NotFound)
                } else if (extra.isNotEmpty()) {
                    respondJson(buildJsonArray {
                        val base = when(id) { 101 -> MOCK_EVENT_101_TICKET_TYPES_JSON; 102 -> MOCK_EVENT_102_TICKET_TYPES_JSON; else -> "[]" }
                        Json.parseToJsonElement(base).jsonArray.filter { old -> extra.none { it["id"] == old.jsonObject["id"] } }.forEach { add(it) }
                        extra.filter { it["is_hidden"]?.jsonPrimitive?.content == "false" }.forEach { add(it) }
                    }.toString(),HttpStatusCode.OK)
                } else
                when (path.removePrefix("/events/").substringBefore('/').toInt()) {
                    101 -> respondJson(MOCK_EVENT_101_TICKET_TYPES_JSON, HttpStatusCode.OK)
                    102 -> respondJson(MOCK_EVENT_102_TICKET_TYPES_JSON, HttpStatusCode.OK)
                    103 -> respondJson("[]", HttpStatusCode.OK)
                    104 -> {
                        event104TicketAttempts++
                        if (event104TicketAttempts == 1) {
                            respondJson(MOCK_TICKETS_ERROR_JSON, HttpStatusCode.InternalServerError)
                        } else {
                            respondJson("[]", HttpStatusCode.OK)
                        }
                    }
                    else -> respondJson(MOCK_EVENT_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                }
            }
            request.method == HttpMethod.Get && path.startsWith("/events/") -> {
                val eventId = path.substringAfterLast('/').toIntOrNull()
                val event = eventId?.let { id ->
                    val changed = createdEvents.values.flatten().firstOrNull { it["id"]?.jsonPrimitive?.int == id }
                    if (changed != null) changed.takeIf { it["status"]?.jsonPrimitive?.content == "published" && it["visibility"]?.jsonPrimitive?.content != "private" }
                    else mockEvents.firstOrNull { item -> item.jsonObject["id"]?.jsonPrimitive?.int == id }
                }
                if (event != null) {
                    respondJson(event.toString(), HttpStatusCode.OK)
                } else {
                    respondJson(MOCK_EVENT_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                }
            }
            else -> error("Unexpected mock request: ${request.method.value} ${request.url}")
        }
    }) {
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
