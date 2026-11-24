package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.*
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EventSalesTest {
    @Test fun freePendingPaidCancelledAndRefundedOrders() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val tickets = CreateTicketTypeViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val vm = EventSalesViewModel(client, requests, session, "http://test")
        suspend fun reload() { vm.reload(); vm.state.first { !it.loading }; assertFalse(vm.state.value.error) }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            vm.open(101, "Retry"); vm.state.first { !it.loading }; assertTrue(vm.state.value.error)
            reload(); assertEquals(101, vm.state.value.sales?.eventId)
            val typeIds = listOf(0, 2500).map { price ->
                tickets.open(103); tickets.update("name", "Type $price"); tickets.update("price", price.toString())
                tickets.update("quantity", "10"); tickets.update("sales_start", "2026-01-01T10:00:00+05:00")
                tickets.update("sales_end", "2027-02-01T10:00:00+05:00")
                tickets.save(); tickets.state.first { !it.saving }
                tickets.list(103).first { it.price == price }.id.also { tickets.close() }
            }
            vm.open(103, "Sales"); vm.state.first { !it.loading }
            assertEquals(0L, vm.state.value.sales?.grossSales)
            assertTrue(vm.state.value.sales!!.ticketTypes.all { it.sold == 0 && it.reserved == 0 && it.available == 10 })
            val free = orders.createOrder(103, "Free buyer", listOf(CreateOrderItem(typeIds[0], 2)), "mock-access-token")
            assertEquals("confirmed", free.status); reload()
            assertEquals(2, vm.state.value.sales!!.ticketTypes.first { it.ticketTypeId == typeIds[0] }.sold)
            assertEquals(0, vm.state.value.sales?.paidOrders)
            val pending = orders.createOrder(103, "Pending buyer", listOf(CreateOrderItem(typeIds[1], 2)), "mock-access-token")
            assertEquals("pending", pending.status); reload()
            assertEquals(2, vm.state.value.sales!!.ticketTypes.first { it.ticketTypeId == typeIds[1] }.reserved)
            assertEquals(0L, vm.state.value.sales?.grossSales)
            orders.cancelOrder(pending.id, "mock-access-token"); reload()
            assertEquals(0, vm.state.value.sales!!.ticketTypes.first { it.ticketTypeId == typeIds[1] }.reserved)
            val payable = orders.createOrder(103, "Paid buyer", listOf(CreateOrderItem(typeIds[1], 2)), "mock-access-token")
            orders.payOrder(payable.id, "mock-access-token"); reload()
            val sales = requireNotNull(vm.state.value.sales)
            assertEquals(5000L, sales.grossSales); assertEquals(5000L, sales.estimatedPayout)
            assertEquals(0L, sales.refunds); assertEquals(0L, sales.processingFees); assertEquals(1, sales.paidOrders)
            assertEquals(TicketTypeSales(typeIds[1], "Type 2500", 2500, 10, 2, 0, 8), sales.ticketTypes.first { it.ticketTypeId == typeIds[1] })
            orders.cancelOrder(free.id, "mock-access-token"); reload()
            assertEquals(0, vm.state.value.sales!!.ticketTypes.first { it.ticketTypeId == typeIds[0] }.sold)
            // Persist the seeded event for the existing event-cancellation simulation.
            assertEquals(200, client.patch("http://test/events/103") {
                bearerAuth("mock-access-token"); contentType(ContentType.Application.Json); setBody("""{"title":"Sales cancellation"}""")
            }.status.value)
            assertEquals(200, client.patch("http://test/events/103/cancel") { bearerAuth("mock-access-token") }.status.value)
            reload(); assertEquals(5000L, vm.state.value.sales?.refunds)
            assertEquals(5000L, vm.state.value.sales?.grossSales); assertEquals(0L, vm.state.value.sales?.estimatedPayout)
            assertEquals(1, vm.state.value.sales?.paidOrders)
            assertTrue(vm.state.value.sales!!.ticketTypes.all { it.sold == 0 && it.reserved == 0 && it.available == 10 })
            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer")); reload()
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            create.open(); create.update("title", "Zero sales")
            create.update("start_time", "2027-02-01T10:00:00+05:00"); create.update("end_time", "2027-02-01T12:00:00+05:00")
            create.save(); create.state.first { !it.saving }
            vm.open(requireNotNull(create.state.value.created).id, "Zero"); vm.state.first { !it.loading }
            assertEquals(0L, vm.state.value.sales?.grossSales); assertTrue(vm.state.value.sales!!.ticketTypes.isEmpty())
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            assertNull(vm.state.value.eventId); assertNull(vm.state.value.sales)
            vm.open(103, "Foreign"); vm.state.first { !it.loading }; assertTrue(vm.state.value.denied)
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertEquals(403, client.get("http://test/events/103/sales") { bearerAuth("mock-empty-access-token") }.status.value)
            vm.open(103, "Other organizer's event"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.denied); assertNull(vm.state.value.sales)
            create.open(); create.update("title", "Second account zero sales")
            create.update("start_time", "2027-02-01T10:00:00+05:00"); create.update("end_time", "2027-02-01T12:00:00+05:00")
            create.save(); create.state.first { !it.saving }
            vm.open(requireNotNull(create.state.value.created).id, "Own event"); vm.state.first { !it.loading }
            assertFalse(vm.state.value.denied); assertFalse(vm.state.value.error)
            assertEquals(0L, vm.state.value.sales?.grossSales); assertEquals(0L, vm.state.value.sales?.refunds)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); tickets.viewModelScope.cancel(); create.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun expiredReservationsAndLongAmounts() {
        fun order(status: String, amount: Long, paid: Boolean, expires: String = "2026-11-24T00:00:00Z") = buildJsonObject {
            put("event_id", 101); put("status", status); put("total_amount", amount)
            put("paid_at", if (paid) JsonPrimitive("2026-11-23T00:00:00Z") else JsonNull)
            put("expires_at", expires)
            putJsonArray("items") { add(buildJsonObject { put("ticket_type_id", 1); put("quantity", 2) }) }
        }
        val type = buildJsonObject { put("id", 1); put("event_id", 101); put("name", "Large amount"); put("price", 2000000000); put("quantity", 20) }
        val summary = Json.decodeFromString<EventSales>(mockSalesSummary(101, listOf(
            order("confirmed", 4000000000, true), order("pending", 4000000000, false),
            order("pending", 4000000000, false, "2026-11-22T00:00:00Z"),
            order("cancelled", 4000000000, false), order("refunded", 4000000000, true),
        ), listOf(type), Instant.parse("2026-11-23T00:00:00Z")).toString())
        assertEquals(8000000000L, summary.grossSales); assertEquals(4000000000L, summary.refunds)
        assertEquals(4000000000L, summary.estimatedPayout); assertEquals(2, summary.paidOrders)
        assertEquals(2, summary.ticketTypes.single().sold); assertEquals(2, summary.ticketTypes.single().reserved)
        assertEquals(16, summary.ticketTypes.single().available)
    }

    @Test fun serverValuesAndLateResponsesStayInTheirSessionAndEvent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val eventGate = CompletableDeferred<Unit>(); val sessionGate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.removePrefix("/events/").substringBefore('/').toInt()
            if (id == 101) eventGate.await()
            if (id == 103) sessionGate.await()
            respond("""{"event_id":$id,"currency":"KZT","is_simulated":true,"gross_sales":5000000000,"processing_fees":17,"refunds":29,"estimated_payout":123,"paid_orders":9,"ticket_types":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = EventSalesViewModel(client, requests, session, "http://test")
        try {
            runCurrent(); vm.open(101, "Old"); runCurrent(); vm.open(102, "New"); vm.state.first { !it.loading }
            eventGate.complete(Unit); runCurrent(); assertEquals(102, vm.state.value.sales?.eventId)
            assertEquals(5000000000L, vm.state.value.sales?.grossSales); assertEquals(123L, vm.state.value.sales?.estimatedPayout)
            vm.open(103, "Old account"); runCurrent(); assertNull(vm.state.value.sales)
            session.clear(); session.save(AuthTokens("other", "other-refresh", "bearer")); runCurrent()
            sessionGate.complete(Unit); runCurrent(); assertNull(vm.state.value.sales); assertNull(vm.state.value.eventId)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
