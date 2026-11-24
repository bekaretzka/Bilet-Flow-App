package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizerOrdersTest {
    @Test fun paginationRefundValidationDoubleSubmitAndDownstreamUpdates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var failList = false
        var hold: CompletableDeferred<Unit>? = null
        var posts = 0
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.build().encodedPath
            if (path.startsWith("/organizer/") && path.endsWith("/orders")) {
                request.url.parameters["limit"] = "1"
                if (failList) { failList = false; throw kotlinx.io.IOException("List unavailable") }
            }
            if (path.endsWith("/refund")) { posts++; hold?.await() }
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val auth = AuthRepositoryImpl(client, "http://test")
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, auth, session, backgroundScope)
        val vm = OrganizerOrdersViewModel(client, requests, session, "http://test")
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val attendees = AttendeesViewModel(client, requests, session, "http://test")
        val sales = EventSalesViewModel(client, requests, session, "http://test")
        val repo = OrganizerOrdersRepository(client, requests, "http://test")
        suspend fun settled() { vm.state.first { !it.loading && !it.detailLoading && !it.refundBusy } }
        suspend fun approve() {
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
        }
        try {
            runCurrent(); approve()
            failList = true; vm.open(101,"First"); settled(); assertNotNull(vm.state.value.error)
            vm.reload(); settled(); assertEquals(1, vm.state.value.orders.size); assertNotNull(vm.state.value.nextCursor)
            vm.more(); settled(); assertEquals(listOf(7101,7102),vm.state.value.orders.map { it.id }); assertNull(vm.state.value.nextCursor)
            assertEquals(listOf(7102),repo.list(101,status = "confirmed").items.map { it.id })
            assertEquals(422,assertFailsWith<OrdersApiException> { repo.list(102,cursor = vm.state.value.nextCursor ?: "orders-101-all-after-7101") }.statusCode)
            vm.select(7102); settled(); vm.askRefund(); assertFalse(vm.state.value.confirming) // Free order.
            vm.back(); assertEquals(101,vm.state.value.eventId); assertNull(vm.state.value.selectedId)
            vm.select(7101); settled(); vm.askRefund(); assertFalse(vm.state.value.confirming) // Pending.
            val paid = orders.payOrder(7101,"mock-access-token")
            vm.checkOrder(); settled(); assertTrue(vm.state.value.order!!.refundable)
            vm.askRefund(); vm.reason(" x "); vm.confirmRefund(); assertTrue(vm.state.value.invalidReason); assertEquals(0, posts)
            vm.dismissRefund(); vm.confirmRefund(); runCurrent(); assertEquals(0, posts)
            vm.askRefund(); vm.reason("refund-failure"); vm.confirmRefund(); settled()
            assertEquals("PaymentFailedError",vm.state.value.refundError); assertEquals(1,posts)
            hold = CompletableDeferred()
            vm.askRefund(); vm.reason("  Full test refund  "); vm.confirmRefund(); vm.confirmRefund(); runCurrent()
            assertTrue(vm.state.value.refundBusy); assertEquals(2,posts)
            vm.back(); assertEquals(7101,vm.state.value.selectedId)
            hold!!.complete(Unit); settled(); hold = null
            assertEquals("refunded", vm.state.value.order!!.status); assertEquals(5000L,vm.state.value.order!!.totalAmount)
            assertNotNull(vm.state.value.order!!.refundedAt); assertTrue(vm.state.value.refundSucceeded)
            vm.askRefund(); vm.confirmRefund(); runCurrent(); assertEquals(2,posts)
            assertEquals("refunded",orders.getOrder(7101,"mock-access-token").status)
            assertTrue(orders.getMyOrders("mock-access-token").first { it.id == 7101 }.tickets.all { it.status == "refunded" })
            attendees.open(101,"First"); attendees.state.first { !it.loading }
            if(attendees.state.value.error) { attendees.reload(); attendees.state.first { !it.loading } }
            assertEquals("refunded",attendees.state.value.attendees.first { it.orderId == 7101 }.status)
            sales.open(101,"First"); sales.state.first { !it.loading }
            if(sales.state.value.error) { sales.reload(); sales.state.first { !it.loading } }
            assertEquals(5000L,sales.state.value.sales!!.refunds); assertEquals(0L,sales.state.value.sales!!.estimatedPayout)
            val scan = client.post("http://test/events/101/check-in") {
                bearerAuth("mock-access-token"); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("code",paid.tickets.single().code) })
            }.body<CheckInResponse>()
            assertEquals("refunded",scan.result)
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.get(102,7101) }.statusCode)
            session.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            vm.back(); vm.reload(); settled(); assertNull(vm.state.value.error)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            vm.open(103,"Empty"); settled(); assertTrue(vm.state.value.orders.isEmpty())
            auth.register("refund-other@example.kz","password123")
            session.save(auth.login("refund-other@example.kz","password123")); runCurrent(); approve()
            assertNull(vm.state.value.eventId)
            assertEquals(403,assertFailsWith<OrdersApiException> { repo.list(101) }.statusCode)
        } finally {
            hold?.complete(Unit)
            listOf(vm,organizer,attendees,sales).forEach { it.viewModelScope.cancel() }
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun lostRefundResponseIsVerifiedThroughOrganizerGetWithoutSecondPostAndConflictRefreshes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var posts = 0
        var failGet = false
        client.plugin(HttpSend).intercept { request ->
            if(request.url.build().encodedPath.endsWith("/refund")) posts++
            if(failGet && request.url.build().encodedPath == "/organizer/events/102/orders/7103") {
                failGet = false; throw kotlinx.io.IOException("Status check unavailable")
            }
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val orders = OrdersRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(orders,AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val organizer = OrganizerViewModel(client,requests,session,"http://test")
        val vm = OrganizerOrdersViewModel(client,requests,session,"http://test")
        suspend fun settled() { vm.state.first { !it.loading && !it.detailLoading && !it.refundBusy } }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            orders.payOrder(7103,"mock-access-token")
            vm.open(102,"Second"); settled(); vm.select(7103); settled()
            vm.askRefund(); vm.reason("refund-unknown"); vm.confirmRefund(); settled()
            assertTrue(7103 in vm.state.value.unknown); assertEquals(1,posts)
            vm.askRefund(); vm.confirmRefund(); runCurrent(); assertFalse(vm.state.value.confirming); assertEquals(1,posts)
            failGet = true; vm.checkOrder(); settled()
            assertTrue(7103 in vm.state.value.unknown); assertNotNull(vm.state.value.detailError)
            vm.askRefund(); vm.confirmRefund(); runCurrent(); assertEquals(1,posts)
            vm.checkOrder(); settled()
            assertEquals("refunded",vm.state.value.order!!.status); assertTrue(vm.state.value.unknown.isEmpty()); assertEquals(1,posts)
            vm.back(); vm.reload(); settled(); assertEquals("refunded",vm.state.value.orders.single().status)

            orders.payOrder(7101,"mock-access-token")
            vm.open(101,"First"); settled(); vm.select(7101); settled(); vm.askRefund(); vm.reason("Concurrent change")
            orders.refundOrder(7101,"Another device","mock-access-token")
            vm.confirmRefund(); settled()
            assertEquals("OrderNotRefundableError",vm.state.value.refundError)
            assertEquals("refunded",vm.state.value.order!!.status); assertFalse(vm.state.value.order!!.refundable)
        } finally { vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun lateResponsesCannotRestoreAnotherEventAccountOrSelectedOrder() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val listGate = CompletableDeferred<Unit>(); val detailGate = CompletableDeferred<Unit>()
        val accountGate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            if(path == "/organizer/events/101/orders") withContext(NonCancellable) { listGate.await() }
            if(path.endsWith("/7101")) withContext(NonCancellable) { detailGate.await() }
            if(path == "/organizer/events/103/orders") withContext(NonCancellable) { accountGate.await() }
            val body = if(path.endsWith("/7101")) """{"id":7101,"event_id":101,"status":"confirmed","total_amount":5000,"buyer_name":"Buyer","buyer_email":"b@example.kz","created_at":"2026-11-24T10:00:00+05:00","paid_at":"2026-11-24T10:01:00+05:00","refunded_at":null}"""
                else """{"items":[],"next_cursor":null}"""
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("a","r","bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val vm = OrganizerOrdersViewModel(client,requests,session,"http://test")
        try {
            runCurrent(); vm.open(101,"Old"); runCurrent(); vm.select(7101); runCurrent()
            vm.open(102,"New"); vm.state.first { !it.loading }
            listGate.complete(Unit); detailGate.complete(Unit); runCurrent()
            assertEquals(102,vm.state.value.eventId); assertNull(vm.state.value.order); assertTrue(vm.state.value.orders.isEmpty())
            vm.open(103,"Old account"); runCurrent(); session.save(AuthTokens("other","other-r","bearer")); runCurrent()
            accountGate.complete(Unit); runCurrent()
            assertNull(vm.state.value.eventId); assertTrue(vm.state.value.orders.isEmpty())
        } finally {
            listGate.complete(Unit); detailGate.complete(Unit); accountGate.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }
}
