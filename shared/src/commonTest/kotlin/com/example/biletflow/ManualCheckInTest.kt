package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ManualCheckInTest {
    @Test fun statefulEntryRepeatUnknownAndBuyerTicket() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var posts = 0
        client.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.build().encodedPath.endsWith("/check-in")) posts++
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        suspend fun settled() { vm.state.first { !it.checkingIn && !it.loadingStats && !it.searching } }
        suspend fun find(code: String) {
            vm.query(code); vm.search(); settled()
            if (vm.state.value.searchError) { vm.search(); settled() }
        }
        suspend fun raw(eventId: Int, code: String) = client.post("http://test/events/$eventId/check-in") {
            bearerAuth(session.tokens.value!!.accessToken); contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject { put("code", kotlinx.serialization.json.JsonPrimitive(code)) })
        }.body<CheckInResponse>()
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            val event = EventsRepositoryImpl(client, "http://test").getEvent(101)
            vm.open(event); settled(); if (vm.state.value.statsError) { vm.reloadStats(); settled() }
            val initial = orders.getOrder(7102, "mock-access-token").tickets.single()
            find(initial.code)
            vm.askCheckIn(vm.state.value.tickets.single()); vm.dismissCheckIn(); vm.confirmCheckIn(); runCurrent()
            assertEquals(0, posts)
            vm.askCheckIn(vm.state.value.tickets.single())
            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            vm.confirmCheckIn(); vm.confirmCheckIn(); settled()
            assertEquals(2, posts) // Explicit 401 plus one replay; double click adds no request.
            assertEquals("checked_in", vm.state.value.checkInResult?.result)
            assertEquals(1, vm.state.value.stats?.checkedIn)
            assertEquals("checked_in", vm.state.value.tickets.single().status)
            val buyer = orders.getOrder(7102, session.tokens.value!!.accessToken).tickets.single()
            assertEquals("checked_in", buyer.status); assertNotNull(buyer.checkedInAt)
            vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); settled()
            assertEquals("already_checked_in", vm.state.value.checkInResult?.result)
            assertEquals(1, vm.state.value.stats?.checkedIn)
            assertEquals("wrong_event", raw(102, initial.code).result)
            assertEquals("invalid", raw(101, "NO-SUCH-CODE").result)
            val cancelled = orders.createOrder(101, "Cancelled", listOf(CreateOrderItem(1002, 1)), session.tokens.value!!.accessToken)
            orders.cancelOrder(cancelled.id, session.tokens.value!!.accessToken)
            assertEquals("cancelled", raw(101, cancelled.tickets.single().code).result)

            val unknown = orders.createOrder(101, "checkin-unknown", listOf(CreateOrderItem(1002, 1)), session.tokens.value!!.accessToken)
            find(unknown.tickets.single().code)
            val before = posts
            vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); vm.confirmCheckIn(); settled()
            assertNotNull(vm.state.value.unknownCode); assertEquals(before + 1, posts)
            vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); runCurrent()
            assertEquals(before + 1, posts)
            vm.verifyUnknown(); settled()
            assertNull(vm.state.value.unknownCode); assertTrue(vm.state.value.verifiedAfterUnknown)
            assertEquals("checked_in", vm.state.value.tickets.single().status)
            assertEquals(before + 1, posts)
            assertEquals("checked_in", orders.getOrder(unknown.id, session.tokens.value!!.accessToken).tickets.single().status)
            // Persist the seeded event in the editable mock store before changing its status.
            assertEquals(200, client.patch("http://test/events/101") {
                bearerAuth(session.tokens.value!!.accessToken); contentType(ContentType.Application.Json)
                setBody("""{"title":"Check-in cancellation fixture"}""")
            }.status.value)
            assertEquals(200, client.patch("http://test/events/101/cancel") { bearerAuth(session.tokens.value!!.accessToken) }.status.value)
            assertEquals("event_cancelled", raw(101, initial.code).result)
            assertEquals(201, client.post("http://test/events/101/staff") {
                bearerAuth(session.tokens.value!!.accessToken); contentType(ContentType.Application.Json)
                setBody("""{"email":"empty@biletflow.kz"}""")
            }.status.value)
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            assertEquals("event_cancelled", raw(101, initial.code).result) // Assigned user has no organizer role.
            vm.open(event.copy(status = "cancelled")); settled(); find(initial.code)
            vm.askCheckIn(vm.state.value.tickets.single())
            assertEquals(204, client.delete("http://test/events/101/staff/101") { bearerAuth("mock-access-token") }.status.value)
            vm.confirmCheckIn(); settled()
            assertTrue(vm.state.value.denied); assertTrue(vm.state.value.tickets.isEmpty())
            session.clear(); runCurrent(); assertNull(vm.state.value.checkInResult); assertNull(vm.state.value.unknownCode)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun everyResultRefreshFailureAndLateResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var result = "refunded"
        var failedReads = false
        var hold: CompletableDeferred<Unit>? = null
        val ticket = """{"id":1,"code":"TEST-CODE","attendee_name":"Name","ticket_type_name":"Type","status":"valid","checked_in_at":null}"""
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                hold?.await()
                respond("""{"result":"$result","message":"Server message","ticket":$ticket}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else if (failedReads) respond("""{"detail":"Unavailable"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(if (request.url.encodedPath.endsWith("/stats")) """{"registered":1,"checked_in":0,"not_checked_in":1,"cancelled":0,"refunded":0}""" else "[$ticket]",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        val event = Event(101, 31, "Event", startTime = "2027-01-01T10:00:00+05:00", endTime = "2027-01-01T12:00:00+05:00", status = "published", visibility = "public")
        suspend fun settled() { vm.state.first { !it.loadingStats && !it.searching && !it.checkingIn } }
        try {
            runCurrent(); vm.open(event); settled()
            for (value in listOf("checked_in", "already_checked_in", "invalid", "wrong_event", "cancelled", "refunded", "event_cancelled", "future_result")) {
                result = value; vm.query("Name"); vm.search(); settled()
                vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); settled()
                assertEquals(value, vm.state.value.checkInResult?.result)
                assertNotNull(checkInResultLabel(value))
            }
            assertEquals(checkInResultLabel("future_result"), checkInResultLabel("already_used"))
            failedReads = true
            vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); settled()
            assertTrue(vm.state.value.statsError); assertTrue(vm.state.value.searchError)
            assertEquals("future_result", vm.state.value.checkInResult?.result)
            failedReads = false; vm.search(); settled()
            hold = CompletableDeferred()
            vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); runCurrent()
            vm.open(event.copy(id = 102)); settled()
            hold.complete(Unit); runCurrent(); assertNull(vm.state.value.checkInResult)
            hold = CompletableDeferred(); vm.query("Name"); vm.search(); settled()
            vm.askCheckIn(vm.state.value.tickets.single()); vm.confirmCheckIn(); runCurrent()
            session.clear(); runCurrent(); hold.complete(Unit); runCurrent()
            assertNull(vm.state.value.event); assertNull(vm.state.value.checkInResult)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
