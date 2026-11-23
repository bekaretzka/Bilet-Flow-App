package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PaidSalesActivationTest {
    @Test fun activationRecoveryIsolationAndVisitorPurchase() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val counts = mutableMapOf<String, Int>()
        var hold: CompletableDeferred<Unit>? = null
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.build().encodedPath
            if (request.method == HttpMethod.Post && "/paid-sales/" in path) {
                counts[path] = counts.getOrElse(path) { 0 } + 1
                hold?.await()
            }
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val orders = OrdersRepositoryImpl(client, "http://test")
        val requests = SessionOrdersRepository(orders, AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val tickets = CreateTicketTypeViewModel(client, requests, session, "http://test")
        val vm = PaidSalesViewModel(client, requests, session, "http://test")
        val attendees = AttendeesViewModel(client, requests, session, "http://test")
        suspend fun settled() { vm.state.first { !it.loading && !it.activationBusy && !it.submitting } }
        suspend fun action(name: String) {
            vm.askAction(name); assertEquals(name, vm.state.value.confirmation)
            vm.confirmAction(); vm.confirmAction(); settled()
        }
        suspend fun raw(id: Int, action: String) = client.post("http://test/events/$id/paid-sales/$action") {
            bearerAuth(session.tokens.value!!.accessToken)
            if (action == "pay-fee") { contentType(ContentType.Application.Json); setBody("{}") }
        }
        try {
            runCurrent()
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            val events = listOf("fee-unknown", "Second event", "fee-failure").map { title ->
                create.open(); create.update("title", title)
                create.update("start_time", "2027-02-01T10:00:00+05:00")
                create.update("end_time", "2027-02-01T12:00:00+05:00")
                create.update("venue_name", "Test Hall"); create.update("venue_address", "Test Address")
                create.save(); create.state.first { !it.saving }
                val event = requireNotNull(create.state.value.created)
                create.reset()
                tickets.open(event.id); tickets.update("name", "Paid entry"); tickets.update("price", "2500")
                tickets.update("quantity", "50"); tickets.update("sales_start", "2026-01-01T10:00:00+05:00")
                tickets.update("sales_end", "2027-02-01T10:00:00+05:00")
                tickets.save(); tickets.state.first { !it.saving }
                event.id to tickets.list(event.id).single().id.also { tickets.close() }
            }
            val (id, ticketId) = events.first()
            assertEquals(200, client.patch("http://test/events/$id/publish") { bearerAuth("mock-access-token") }.status.value)
            vm.open(id, "First"); settled()
            assertFalse(vm.state.value.data!!.canActivate)
            vm.askAction("activate"); assertNull(vm.state.value.confirmation)
            val unmet = raw(id, "activate")
            assertEquals(409, unmet.status.value)
            assertTrue(unmet.bodyAsText().contains("ActivationRequirementsNotMetError"))
            val rejected = assertFailsWith<OrdersApiException> { orders.createOrder(id, "Visitor", listOf(CreateOrderItem(ticketId, 1)), "mock-access-token") }
            assertEquals("PaidSalesNotActiveError", rejected.errorCode)

            vm.openRequirement("identity_verified"); vm.updateField("legal_name", "Test Organizer")
            vm.updateField("contact_phone", "+7 701 123 45 67"); vm.submitRequirement(); settled()
            vm.openRequirement("payout_account_connected"); vm.updateField("holder_name", "Test Organizer")
            vm.updateField("bank_name", "Test Bank"); vm.updateField("iban", "KZ86 125K ZT50 0410 0100")
            vm.submitRequirement(); settled()
            vm.askAction("accept-terms"); vm.dismissAction(); vm.confirmAction(); runCurrent()
            assertNull(counts["/events/$id/paid-sales/accept-terms"])
            action("accept-terms")
            assertEquals(1, counts["/events/$id/paid-sales/accept-terms"])
            assertTrue(vm.state.value.data!!.checklist.getValue("terms_accepted"))
            action("pay-fee")
            assertTrue(vm.state.value.activationUnknown)
            vm.askAction("pay-fee"); vm.confirmAction(); runCurrent()
            assertEquals(1, counts["/events/$id/paid-sales/pay-fee"])
            vm.reload(); settled()
            assertFalse(vm.state.value.activationUnknown)
            assertTrue(vm.state.value.data!!.canActivate)
            action("activate")
            assertEquals("active", vm.state.value.data?.status)
            assertEquals(2, counts["/events/$id/paid-sales/activate"]) // Earlier direct request checked unmet requirements.
            val order = orders.createOrder(id, "Visitor", listOf(CreateOrderItem(ticketId, 1)), "mock-access-token")
            assertEquals("pending", order.status); assertEquals(2500L, order.totalAmount)
            attendees.open(id, "First"); attendees.state.first { !it.loading }
            assertTrue(attendees.state.value.attendees.isEmpty())
            val paid = orders.payOrder(order.id, "mock-access-token")
            assertEquals("confirmed", paid.status)
            attendees.reload(); attendees.state.first { !it.loading }
            assertEquals(paid.tickets.single().id, attendees.state.value.attendees.single().ticketId)
            assertEquals("Paid entry", attendees.state.value.attendees.single().ticketTypeName)
            assertEquals("valid", attendees.state.value.attendees.single().status)
            val png = orders.getTicketQr(paid.tickets.single().id, "mock-access-token")
            assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.take(8).toByteArray())
            // Existing event cancellation simulates a refund for a paid confirmed order.
            assertEquals(200, client.patch("http://test/events/$id/cancel") { bearerAuth("mock-access-token") }.status.value)
            attendees.reload(); attendees.state.first { !it.loading }
            assertEquals("refunded", attendees.state.value.attendees.single().status)

            val secondId = events[1].first
            vm.open(secondId, "Second"); settled()
            assertEquals("not_started", vm.state.value.data?.status)
            assertFalse(vm.state.value.data!!.checklist.getValue("terms_accepted"))
            assertFalse(vm.state.value.data!!.checklist.getValue("fee_paid"))
            assertTrue(vm.state.value.data!!.checklist.getValue("identity_verified"))
            assertTrue(vm.state.value.data!!.checklist.getValue("payout_account_connected"))
            action("accept-terms")
            // Another device pays after the last GET: the stale button receives a conflict and refreshes.
            assertEquals(200, raw(secondId, "pay-fee").status.value)
            action("pay-fee")
            assertEquals("ActivationFeeAlreadyPaidError", vm.state.value.activationError)
            assertTrue(vm.state.value.data!!.checklist.getValue("fee_paid"))
            vm.open(events[2].first, "Declined"); settled()
            action("pay-fee")
            assertEquals("PaymentFailedError", vm.state.value.activationError)
            assertFalse(vm.state.value.data!!.checklist.getValue("fee_paid"))
            action("pay-fee")
            assertTrue(vm.state.value.data!!.checklist.getValue("fee_paid"))
            assertEquals(409, raw(103, "activate").status.value)

            // A response from a different event/account cannot replace the current screen.
            vm.open(secondId, "Second"); settled()
            hold = CompletableDeferred()
            vm.askAction("activate"); vm.confirmAction(); runCurrent()
            vm.open(events[2].first, "Third"); settled()
            hold!!.complete(Unit); runCurrent()
            assertEquals(events[2].first, vm.state.value.data?.eventId)
            assertNotEquals("active", vm.state.value.data?.status)
            hold = CompletableDeferred()
            vm.askAction("accept-terms"); vm.confirmAction(); runCurrent()
            session.clear(); runCurrent()
            hold.complete(Unit); runCurrent()
            assertNull(vm.state.value.eventId)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); create.viewModelScope.cancel(); tickets.viewModelScope.cancel()
            attendees.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }
}
