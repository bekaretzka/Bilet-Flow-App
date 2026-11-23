package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AttendeesTest {
    @Test fun freeTicketsRetrySearchCancellationRefreshAndIsolation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val vm = AttendeesViewModel(client, requests, session, "http://test")
        try {
            runCurrent()
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            vm.open(101, "First"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.error)
            vm.reload(); vm.state.first { !it.loading }
            val initial = vm.state.value.attendees.size
            val order = orders.createOrder(101, "Several Tickets", listOf(CreateOrderItem(1002, 2)), "mock-access-token")
            assertEquals("confirmed", order.status)
            vm.reload(); vm.state.first { !it.loading }
            assertEquals(initial + 2, vm.state.value.attendees.size)
            vm.search("  sEvErAl TiCkEtS  ")
            assertEquals(2, vm.state.value.filtered.size)
            assertEquals(2, vm.state.value.filtered.map { it.ticketId }.distinct().size)
            assertTrue(vm.state.value.filtered.all { it.orderId == order.id && it.ticketTypeName != null })
            vm.search(" VISITOR@BILETFLOW.KZ ")
            assertEquals(vm.state.value.attendees.size, vm.state.value.filtered.size)
            vm.search("no such name"); assertTrue(vm.state.value.filtered.isEmpty())
            assertTrue(vm.state.value.attendees.isNotEmpty())
            orders.cancelOrder(order.id, "mock-access-token")
            vm.reload(); vm.state.first { !it.loading }
            assertTrue(vm.state.value.attendees.filter { it.orderId == order.id }.all { it.status == "cancelled" })
            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            vm.reload(); vm.state.first { !it.loading }
            assertFalse(vm.state.value.error)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            vm.open(103, "Empty")
            assertTrue(vm.state.value.attendees.isEmpty()); assertEquals("", vm.state.value.query)
            vm.state.first { !it.loading }
            assertTrue(vm.state.value.attendees.isEmpty()); assertFalse(vm.state.value.error)
            vm.close(); assertNull(vm.state.value.eventId)
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            assertTrue(vm.state.value.attendees.isEmpty())
            vm.open(101, "Foreign"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.denied); assertTrue(vm.state.value.attendees.isEmpty())
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            create.open(); create.update("title", "Second account event")
            create.update("start_time", "2027-02-01T10:00:00+05:00")
            create.update("end_time", "2027-02-01T12:00:00+05:00")
            create.save(); create.state.first { !it.saving }
            vm.open(requireNotNull(create.state.value.created).id, "Own event"); vm.state.first { !it.loading }
            assertFalse(vm.state.value.denied); assertFalse(vm.state.value.error)
            assertTrue(vm.state.value.attendees.isEmpty())
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); create.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun nullableSearchAndLateResponses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val eventGate = CompletableDeferred<Unit>()
        val sessionGate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.removePrefix("/events/").substringBefore('/').toInt()
            if (id == 101) eventGate.await()
            if (id == 103) sessionGate.await()
            respond("""[{"ticket_id":$id,"attendee_name":null,"ticket_type_name":null,"status":"checked_in","order_id":1,"buyer_email":null,"created_at":"2026-11-23T10:00:00+05:00"}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = AttendeesViewModel(client, requests, session, "http://test")
        try {
            runCurrent()
            vm.open(101, "Old"); runCurrent()
            vm.open(102, "New"); vm.state.first { !it.loading }
            assertEquals(102, vm.state.value.attendees.single().ticketId)
            vm.search("  "); assertEquals(1, vm.state.value.filtered.size)
            vm.search("name"); assertTrue(vm.state.value.filtered.isEmpty())
            eventGate.complete(Unit); runCurrent()
            assertEquals(102, vm.state.value.attendees.single().ticketId)
            vm.open(103, "Old account"); runCurrent()
            session.save(AuthTokens("other", "other-refresh", "bearer")); runCurrent()
            sessionGate.complete(Unit); runCurrent()
            assertNull(vm.state.value.eventId); assertTrue(vm.state.value.attendees.isEmpty())
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
