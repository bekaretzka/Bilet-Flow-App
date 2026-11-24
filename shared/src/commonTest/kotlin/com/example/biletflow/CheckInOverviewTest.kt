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
class CheckInOverviewTest {
    @Test fun staffStatsSearchEncodingRefreshAndRevocation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val searches = mutableListOf<String>()
        var checkInPosts = 0
        client.plugin(HttpSend).intercept { request ->
            if (request.url.build().encodedPath.endsWith("/check-in/search")) searches.add(request.url.build().parameters["q"].orEmpty())
            if (request.method == HttpMethod.Post && request.url.build().encodedPath.endsWith("/check-in")) checkInPosts++
            execute(request)
        }
        val auth = AuthRepositoryImpl(client, "http://test")
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, auth, session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val staff = EventStaffViewModel(client, requests, session, "http://test")
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            staff.open(101, "Test"); staff.state.first { !it.loading }; staff.reload(); staff.state.first { !it.loading }
            staff.email("empty@biletflow.kz"); staff.add(); staff.state.first { !it.busy && !it.loading }
            val order = orders.createOrder(101, "A+B & Тест", listOf(CreateOrderItem(1002, 2)), "mock-access-token")
            val event = EventsRepositoryImpl(client, "http://test").getEvent(101)
            session.save(auth.login("empty@biletflow.kz", "password123")); runCurrent()
            vm.open(event); vm.state.first { !it.loadingStats }; assertTrue(vm.state.value.statsError)
            vm.reloadStats(); vm.state.first { !it.loadingStats }
            assertEquals(CheckInStats(3, 0, 3, 0, 0), vm.state.value.stats)
            vm.query("x"); vm.search(); assertTrue(vm.state.value.invalidQuery); assertTrue(searches.isEmpty())
            vm.query("x".repeat(101)); vm.search(); assertTrue(vm.state.value.invalidQuery)
            vm.query("  A+B & Тест  "); runCurrent(); assertTrue(searches.isEmpty())
            vm.search(); vm.state.first { !it.searching }; assertTrue(vm.state.value.searchError)
            assertNotNull(vm.state.value.stats)
            vm.search(); vm.state.first { !it.searching }
            assertEquals(listOf("A+B & Тест", "A+B & Тест"), searches)
            assertEquals(2, vm.state.value.tickets.size)
            assertTrue(vm.state.value.tickets.all { it.status == "valid" && it.checkedInAt == null })
            vm.query(order.tickets.first().code); vm.search(); vm.state.first { !it.searching }
            assertEquals(order.tickets.first().id, vm.state.value.tickets.single().id)
            vm.query("VISITOR@BILETFLOW.KZ"); vm.search(); vm.state.first { !it.searching }
            assertEquals(3, vm.state.value.tickets.size)
            vm.query("no matching ticket"); vm.search(); vm.state.first { !it.searching }
            assertTrue(vm.state.value.searched); assertTrue(vm.state.value.tickets.isEmpty())
            session.rotateTokens(AuthTokens("expired-access", "mock-empty-refresh-token", "bearer"))
            vm.reloadStats(); vm.state.first { !it.loadingStats }
            assertFalse(vm.state.value.statsError)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            orders.cancelOrder(order.id, "mock-access-token")
            vm.reloadStats(); vm.state.first { !it.loadingStats }
            assertEquals(CheckInStats(3, 0, 1, 2, 0), vm.state.value.stats)
            vm.query("A+B & Тест"); vm.search(); vm.state.first { !it.searching }
            assertTrue(vm.state.value.tickets.all { it.status == "cancelled" })
            vm.openScanner(); vm.scannerForeground(true)
            assertTrue(vm.state.value.scannerOpen)
            val revokedScannerEpoch = vm.state.value.scannerEpoch
            assertEquals(204, client.delete("http://test/events/101/staff/101") { bearerAuth("mock-access-token") }.status.value)
            vm.reloadStats(); vm.state.first { !it.loadingStats }; assertTrue(vm.state.value.denied)
            assertFalse(vm.state.value.scannerOpen)
            assertNull(vm.state.value.stats)
            assertTrue(vm.state.value.tickets.isEmpty())
            // A camera frame already queued on the main thread must not submit after revocation.
            vm.scannerFrame(revokedScannerEpoch, listOf(order.tickets.first().code))
            runCurrent()
            assertEquals(0, checkInPosts)
            vm.close(); assertNull(vm.state.value.event)
            vm.open(event.copy(id = 102, title = "Other event")); vm.state.first { !it.loadingStats }
            assertTrue(vm.state.value.denied); assertTrue(vm.state.value.tickets.isEmpty()); assertNull(vm.state.value.stats)
        } finally {
            vm.viewModelScope.cancel(); staff.viewModelScope.cancel(); organizer.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun independentLoadsAndLateQueriesEventsAndAccounts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val sessionGate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters["q"] == "old") gate.await()
            if (request.url.parameters["q"] == "old-session") sessionGate.await()
            val ticketId = if (request.url.parameters["q"] == "old") 999 else 1
            val body = if (request.url.encodedPath.endsWith("/stats"))
                """{"registered":99,"checked_in":7,"not_checked_in":8,"cancelled":3,"refunded":4}"""
            else """[{"id":$ticketId,"code":"TEST","attendee_name":"Name","ticket_type_name":"Type","status":"checked_in","checked_in_at":"2026-11-23T10:00:00+05:00"}]"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("one", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        val event = Event(101, 31, "Event", startTime = "2027-01-01T10:00:00+05:00", endTime = "2027-01-01T12:00:00+05:00", status = "cancelled", visibility = "public")
        try {
            runCurrent(); vm.open(event); vm.state.first { !it.loadingStats }
            assertEquals(CheckInStats(99, 7, 8, 3, 4), vm.state.value.stats)
            vm.query("old"); vm.search(); runCurrent()
            vm.query("new"); vm.search(); vm.state.first { !it.searching }
            assertNotNull(vm.state.value.tickets.single().checkedInAt)
            gate.complete(Unit); runCurrent()
            assertEquals(1, vm.state.value.tickets.single().id)
            vm.reloadStats(); vm.state.first { !it.loadingStats }; assertEquals(1, vm.state.value.tickets.size)
            vm.open(event.copy(id = 102)); assertTrue(vm.state.value.tickets.isEmpty()); assertNull(vm.state.value.stats)
            vm.state.first { !it.loadingStats }
            vm.query("old-session"); vm.search(); runCurrent()
            session.clear(); runCurrent(); sessionGate.complete(Unit); runCurrent()
            assertNull(vm.state.value.event); assertTrue(vm.state.value.tickets.isEmpty()); assertNull(vm.state.value.stats)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
