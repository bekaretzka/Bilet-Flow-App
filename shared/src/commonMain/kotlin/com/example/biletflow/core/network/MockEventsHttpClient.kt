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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun createMockEventsHttpClient(): HttpClient {
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
    var event104TicketAttempts = 0
    val mockOrders = mutableMapOf<Int, String>()
    val paymentAttempts = mutableMapOf<Int, Int>()
    val cancellationAttempts = mutableMapOf<Int, Int>()
    val qrAttempts = mutableMapOf<Int, Int>()
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
                    else -> {
                        // Seeded events: 101 none, 102 partial, 103 active.
                        // Created events start with unmet requirements; title paid-sales-suspended simulates suspension.
                        val status = when {
                            event?.get("title")?.jsonPrimitive?.content == "paid-sales-suspended" -> "suspended"
                            id == 102 -> "pending"
                            id == 103 -> "active"
                            else -> "not_started"
                        }
                        val complete = status in listOf("active", "suspended")
                        respondJson(buildJsonObject {
                            put("event_id", id); put("status", status)
                            put("fee_amount", 5000); put("currency", "KZT"); put("is_simulated", true)
                            putJsonObject("checklist") {
                                put("has_paid_ticket_type", complete || status == "pending" || id == 101 ||
                                    createdTicketTypes[owner].orEmpty().any {
                                        it["event_id"]?.jsonPrimitive?.int == id && (it["price"]?.jsonPrimitive?.int ?: 0) > 0
                                    })
                                put("identity_verified", complete || status == "pending" || profile?.get("identity_verified_at")?.let { it != JsonNull } == true)
                                put("payout_account_connected", complete || profile?.get("payout_account")?.let { it != JsonNull } == true)
                                put("terms_accepted", complete); put("fee_paid", complete)
                            }
                            put("can_activate", false)
                            put("activated_at", if (complete) JsonPrimitive("2026-11-12T10:00:00+05:00") else JsonNull)
                            put("suspended_at", if (status == "suspended") JsonPrimitive("2026-11-13T10:00:00+05:00") else JsonNull)
                            put("suspension_reason", if (status == "suspended") JsonPrimitive("Mock review required") else JsonNull)
                        }.toString(), HttpStatusCode.OK)
                    }
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
                        else if (owner != "Bearer mock-access-token") respondJson(buildJsonArray {
                            createdEvents[owner].orEmpty().forEach { add(it) }
                        }.toString(), HttpStatusCode.OK)
                        else respondJson(buildJsonArray {
                            createdEvents[owner].orEmpty().forEach { add(it) }
                            listOf("draft" to "private", "published" to "public", "cancelled" to "unlisted").forEachIndexed { index, pair ->
                                if (createdEvents[owner].orEmpty().any { it["id"] == mockEvents[index].jsonObject["id"] }) return@forEachIndexed
                                add(buildJsonObject {
                                    mockEvents[index].jsonObject.forEach { (key, value) -> put(key, value) }
                                    put("organizer_id", profile["id"]!!)
                                    put("status", pair.first)
                                    put("visibility", pair.second)
                                })
                            }
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
                        val profile = """{"id":${31 + organizerProfiles.size},"user_id":${100 + organizerProfiles.size},"status":"pending"}"""
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
                        respondJson(buildJsonObject { put("id", 100 + registeredUsers.size); put("email", email) }.toString(), HttpStatusCode.Created)
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
                if (authorization != "Bearer mock-access-token") {
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
                    when {
                        buyerName.equals("offline", ignoreCase = true) ->
                            throw IOException("Mock connection interrupted after sending order")
                        buyerName.equals("conflict", ignoreCase = true) ->
                            respondJson(MOCK_ORDER_CONFLICT_JSON, HttpStatusCode.Conflict)
                        buyerName.isBlank() || items.isEmpty() ->
                            respondJson(MOCK_ORDER_VALIDATION_JSON, HttpStatusCode.UnprocessableEntity)
                        else -> {
                            val orderId = when {
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
                            )
                            mockOrders[orderId] = orderJson
                            respondJson(orderJson, HttpStatusCode.Created)
                        }
                    }
                }
            }
            request.method == HttpMethod.Post && path.matches(Regex("/orders/\\d+/pay")) -> {
                if (authorizationHeader != "Bearer mock-access-token") {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val orderId = path.removePrefix("/orders/").substringBefore('/').toInt()
                    val orderJson = mockOrders[orderId]
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
                if (authorizationHeader != "Bearer mock-access-token") {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val orderId = path.removePrefix("/orders/").substringBefore('/').toInt()
                    val orderJson = mockOrders[orderId]
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
                if (request.headers[HttpHeaders.Authorization]?.startsWith("Bearer mock-user-") == true) {
                    respondJson("[]", HttpStatusCode.OK)
                } else
                when (authorizationHeader) {
                    "Bearer mock-access-token" -> respondJson(
                        buildJsonArray {
                            mockOrders.values.forEach { add(Json.parseToJsonElement(it)) }
                        }.toString(),
                        HttpStatusCode.OK,
                    )
                    "Bearer mock-empty-access-token" -> respondJson("[]", HttpStatusCode.OK)
                    "Bearer mock-history-offline-token" -> throw IOException("Mock order history unavailable")
                    else -> respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/orders/\\d+")) -> {
                if (authorizationHeader != "Bearer mock-access-token") {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val orderId = path.substringAfterLast('/').toInt()
                    mockOrders[orderId]?.let { respondJson(it, HttpStatusCode.OK) }
                        ?: respondJson(MOCK_ORDER_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                }
            }
            request.method == HttpMethod.Get && path.matches(Regex("/tickets/\\d+/qr")) -> {
                if (authorizationHeader != "Bearer mock-access-token") {
                    respondJson(MOCK_ORDER_UNAUTHORIZED_JSON, HttpStatusCode.Unauthorized)
                } else {
                    val ticketId = path.removePrefix("/tickets/").substringBefore('/').toInt()
                    val knownTicket = mockOrders.values.any { orderJson ->
                        Json.parseToJsonElement(orderJson).jsonObject["tickets"]?.jsonArray
                            ?.any { it.jsonObject["id"]?.jsonPrimitive?.int == ticketId } == true
                    }
                    when {
                        !knownTicket -> respondJson(MOCK_TICKET_NOT_FOUND_JSON, HttpStatusCode.NotFound)
                        ticketId % 10 == 1 && qrAttempts.getOrElse(ticketId) { 0 } == 0 -> {
                            qrAttempts[ticketId] = 1
                            throw IOException("Mock QR connection interrupted")
                        }
                        else -> respondPng(MOCK_QR_PNG)
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
): String {
    val prices = mapOf(1001 to 5000, 1002 to 0, 2001 to 0, 2002 to 1000)
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
        put("buyer_email", "visitor@biletflow.kz")
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
                items.forEach { (ticketTypeId, quantity) ->
                    repeat(quantity) { index ->
                        add(buildJsonObject {
                            put("id", orderId * 10 + index)
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

private val MOCK_QR_PNG = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
    0, 0, 0, 1, 0, 0, 0, 1, 8, 4, 0, 0, 0, -75, 28, 12, 2,
    0, 0, 0, 11, 73, 68, 65, 84, 120, -38, 99, 100, -8, 15, 0, 1,
    5, 1, 1, 39, 24, -29, 102, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
)

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
