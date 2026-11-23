package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
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
class ManualUndoTest {
    @Test fun staffUndoConflictUnknownRefreshAndBuyerState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var undoPosts = 0
        var checkInPosts = 0
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.build().encodedPath
            if (request.method == HttpMethod.Post && path.endsWith("/undo")) undoPosts++
            if (request.method == HttpMethod.Post && path.endsWith("/check-in")) checkInPosts++
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        suspend fun settled() { vm.state.first { !it.checkingIn && !it.loadingStats && !it.searching } }
        suspend fun find(code: String) { vm.query(code); vm.search(); settled(); if (vm.state.value.searchError) { vm.search(); settled() } }
        suspend fun ownerPost(route: String, body: String) = client.post("http://test/events/101/check-in$route") {
            bearerAuth("mock-access-token"); contentType(ContentType.Application.Json); setBody(body)
        }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertEquals(201, client.post("http://test/events/101/staff") {
                bearerAuth("mock-access-token"); contentType(ContentType.Application.Json); setBody("""{"email":"empty@biletflow.kz"}""")
            }.status.value)
            val ticket = orders.getOrder(7102, "mock-access-token").tickets.single()
            assertEquals(200, ownerPost("", """{"code":"${ticket.code}"}""").status.value)
            val event = EventsRepositoryImpl(client, "http://test").getEvent(101)
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            vm.open(event); settled(); if (vm.state.value.statsError) { vm.reloadStats(); settled() }
            find(ticket.code)
            val checked = vm.state.value.tickets.single()
            assertEquals("checked_in", checked.status)
            vm.askUndo(checked); vm.dismissCheckIn(); vm.confirmUndo(); runCurrent(); assertEquals(0, undoPosts)
            session.rotateTokens(AuthTokens("expired-access", "mock-empty-refresh-token", "bearer"))
            vm.askUndo(checked); vm.confirmUndo(); vm.confirmUndo()
            vm.askCheckIn(checked); vm.confirmCheckIn(); settled()
            assertEquals(2, undoPosts) // One explicit 401 and one replay; double click adds no request.
            assertEquals(1, checkInPosts)
            assertEquals("valid", vm.state.value.undoResult?.status); assertNull(vm.state.value.undoResult?.checkedInAt)
            assertEquals(0, vm.state.value.stats?.checkedIn); assertEquals(1, vm.state.value.stats?.notCheckedIn)
            val buyer = orders.getOrder(7102, "mock-access-token").tickets.single()
            assertEquals("valid", buyer.status); assertNull(buyer.checkedInAt)
            vm.askUndo(vm.state.value.tickets.single()); assertNull(vm.state.value.confirming)

            ownerPost("", """{"code":"${ticket.code}"}""")
            find(ticket.code); vm.askUndo(vm.state.value.tickets.single())
            ownerPost("/undo", """{"ticket_id":${ticket.id}}""") // Another scanner removed the mark first.
            vm.confirmUndo(); settled()
            assertTrue(vm.state.value.undoAbsent); assertFalse(vm.state.value.checkInError)
            assertEquals("valid", vm.state.value.tickets.single().status)

            val unknown = orders.createOrder(101, "undo-unknown", listOf(CreateOrderItem(1002, 1)), "mock-access-token").tickets.single()
            ownerPost("", """{"code":"${unknown.code}"}""")
            find(unknown.code)
            val before = undoPosts
            val beforeCheck = checkInPosts
            vm.askUndo(vm.state.value.tickets.single()); vm.confirmUndo(); vm.confirmUndo(); settled()
            assertEquals(before + 1, undoPosts); assertNotNull(vm.state.value.unknownCode)
            val stale = vm.state.value.tickets.single()
            vm.askUndo(stale); vm.confirmUndo(); vm.askCheckIn(stale); vm.confirmCheckIn(); runCurrent()
            assertEquals(before + 1, undoPosts); assertEquals(beforeCheck, checkInPosts)
            vm.verifyUnknown(); settled()
            assertNull(vm.state.value.unknownCode); assertEquals("valid", vm.state.value.tickets.single().status)
            assertEquals(before + 1, undoPosts)
            assertEquals("valid", orders.getOrder(7001, "mock-access-token").tickets.single().status)

            ownerPost("", """{"code":"${unknown.code}"}"""); find(unknown.code)
            vm.askUndo(vm.state.value.tickets.single())
            client.delete("http://test/events/101/staff/101") { bearerAuth("mock-access-token") }
            vm.confirmUndo(); settled(); assertTrue(vm.state.value.denied); assertNull(vm.state.value.undoResult)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun retainsUndoResultOnReadFailureAndIgnoresLateMutation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var failReads = false
        var hold: CompletableDeferred<Unit>? = null
        val checked = """{"id":1,"code":"CODE","attendee_name":"Name","ticket_type_name":"Type","status":"checked_in","checked_in_at":"2026-11-23T12:00:00+05:00"}"""
        val valid = """{"id":1,"code":"CODE","attendee_name":"Name","ticket_type_name":"Type","status":"valid","checked_in_at":null}"""
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                hold?.await()
                respond(valid, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else if (failReads) respond("""{"detail":"Unavailable"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(if (request.url.encodedPath.endsWith("/stats")) """{"registered":1,"checked_in":1,"not_checked_in":0,"cancelled":0,"refunded":0}""" else "[$checked]",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        val event = Event(101, 31, "Event", startTime = "2027-01-01T10:00:00+05:00", endTime = "2027-01-01T12:00:00+05:00", status = "published", visibility = "public")
        suspend fun settled() { vm.state.first { !it.checkingIn && !it.loadingStats && !it.searching } }
        try {
            runCurrent(); vm.open(event); settled(); vm.query("Name"); vm.search(); settled()
            failReads = true
            vm.askUndo(vm.state.value.tickets.single()); vm.confirmUndo(); settled()
            assertTrue(vm.state.value.statsError); assertTrue(vm.state.value.searchError)
            assertEquals("valid", vm.state.value.undoResult?.status)
            failReads = false; vm.search(); settled()
            hold = CompletableDeferred()
            vm.askUndo(vm.state.value.tickets.single()); vm.confirmUndo(); runCurrent()
            vm.open(event.copy(id = 102)); settled(); hold.complete(Unit); runCurrent()
            assertNull(vm.state.value.undoResult)
            vm.query("Name"); vm.search(); settled(); hold = CompletableDeferred()
            vm.askUndo(vm.state.value.tickets.single()); vm.confirmUndo(); runCurrent()
            session.clear(); session.save(AuthTokens("other", "other-refresh", "bearer")); runCurrent()
            hold.complete(Unit); runCurrent(); assertNull(vm.state.value.event); assertNull(vm.state.value.undoResult)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
