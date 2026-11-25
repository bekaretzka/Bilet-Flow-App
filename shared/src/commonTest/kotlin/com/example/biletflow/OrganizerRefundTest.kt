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
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizerRefundTest {
    @Test fun refundContractOwnershipAndDownstreamReads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient(publishedFixtureEvents = true)
        val reasons = mutableListOf<String>()
        client.plugin(HttpSend).intercept { request ->
            if (request.url.build().encodedPath.endsWith("/refund")) {
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.headers[HttpHeaders.Authorization]!!.startsWith("Bearer "))
                val payload = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals(setOf("reason"), payload.keys); reasons.add(payload.getValue("reason").jsonPrimitive.content)
            }
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val attendees = AttendeesViewModel(client, requests, session, "http://test")
        val sales = EventSalesViewModel(client, requests, session, "http://test")
        suspend fun attendeesLoaded() {
            attendees.open(101, "Test"); attendees.state.first { !it.loading }
            if (attendees.state.value.error) { attendees.reload(); attendees.state.first { !it.loading } }
        }
        try {
            runCurrent()
            assertFailsWith<IllegalArgumentException> { orders.refundOrder(7101, " x ", "mock-access-token") }
            assertFailsWith<IllegalArgumentException> { orders.refundOrder(7101, "x".repeat(501), "mock-access-token") }
            assertTrue(reasons.isEmpty())
            val notOwner = assertFailsWith<OrdersApiException> { orders.refundOrder(7101, "Test reason", "mock-access-token") }
            assertEquals(403, notOwner.statusCode); assertEquals("EventAccessDeniedError", notOwner.errorCode)
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertEquals("OrderNotRefundableError", assertFailsWith<OrdersApiException> {
                orders.refundOrder(7101, "Pending order", "mock-access-token")
            }.errorCode)
            assertEquals("OrderNotRefundableError", assertFailsWith<OrdersApiException> {
                orders.refundOrder(7102, "Free confirmed order", "mock-access-token")
            }.errorCode)
            // Test fixture comes from buyer payment. There is no organizer-readable order source in the app.
            val paid = orders.payOrder(7101, "mock-access-token")
            assertEquals(101, paid.eventId); assertEquals(5000L, paid.totalAmount)
            val declined = assertFailsWith<OrdersApiException> { orders.refundOrder(paid.id, "refund-failure", "mock-access-token") }
            assertEquals(402, declined.statusCode); assertEquals("PaymentFailedError", declined.errorCode)
            assertEquals("confirmed", orders.getOrder(paid.id, "mock-access-token").status)
            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            val refunded = requests.refundOrder(paid.id, "  Full test refund  ", "ignored")
            assertEquals("refunded", refunded.status); assertEquals(paid.totalAmount, refunded.totalAmount)
            assertNotNull(refunded.refundedAt); assertTrue(refunded.tickets.all { it.status == "refunded" })
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            assertEquals(listOf("Full test refund", "Full test refund"), reasons.takeLast(2)) // Explicit 401 then one replay.
            val repeated = assertFailsWith<OrdersApiException> { requests.refundOrder(paid.id, "Repeated refund", "ignored") }
            assertEquals("OrderNotRefundableError", repeated.errorCode)
            val buyerOrder = orders.getOrder(paid.id, session.tokens.value!!.accessToken)
            assertEquals("refunded", buyerOrder.status)
            assertTrue(orders.getMyOrders(session.tokens.value!!.accessToken).first { it.id == paid.id }.tickets.all { it.status == "refunded" })
            attendeesLoaded()
            assertTrue(attendees.state.value.attendees.filter { it.orderId == paid.id }.all { it.status == "refunded" })
            sales.open(101, "Sales"); sales.state.first { !it.loading }
            if (sales.state.value.error) { sales.reload(); sales.state.first { !it.loading } }
            assertEquals(5000L, sales.state.value.sales?.refunds); assertEquals(0L, sales.state.value.sales?.estimatedPayout)
            val scan = client.post("http://test/events/101/check-in") {
                bearerAuth(session.tokens.value!!.accessToken); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("code", paid.tickets.single().code) })
            }.body<CheckInResponse>()
            assertEquals("refunded", scan.result)
            assertEquals("OrderNotFoundError", assertFailsWith<OrdersApiException> {
                orders.refundOrder(paid.id, "Foreign organizer", "mock-empty-access-token")
            }.errorCode)
            assertEquals(404, assertFailsWith<OrdersApiException> { orders.getOrder(paid.id, "mock-empty-access-token") }.statusCode)

            // The lost response is not retried. Attendees verify only ticket status, not order amount/status.
            val unknownPaid = orders.payOrder(7103, session.tokens.value!!.accessToken)
            val before = reasons.size
            assertFails { requests.refundOrder(unknownPaid.id, "refund-unknown", "ignored") }
            assertEquals(before + 1, reasons.size)
            attendees.open(102, "Verification"); attendees.state.first { !it.loading }
            assertTrue(attendees.state.value.attendees.any { it.orderId == unknownPaid.id && it.status == "refunded" })
            assertEquals(before + 1, reasons.size)
            assertEquals("refunded", orders.getOrder(unknownPaid.id, session.tokens.value!!.accessToken).status)
        } finally {
            organizer.viewModelScope.cancel(); attendees.viewModelScope.cancel(); sales.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun lateRefundResponseCannotEnterNewSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            gate.await()
            respond("""{"id":1,"event_id":101,"status":"refunded","total_amount":1000,"buyer_name":"Test","buyer_email":"test@example.kz","created_at":"2026-11-24T00:00:00Z","items":[],"tickets":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("old", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        try {
            val pending = async { requests.refundOrder(1, "Test refund", "ignored") }
            runCurrent(); session.clear(); session.save(AuthTokens("new", "other-refresh", "bearer"))
            gate.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals("new", session.tokens.value?.accessToken)
        } finally { client.close() }
    }
}
