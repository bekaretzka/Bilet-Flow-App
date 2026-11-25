package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.*
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.events.loadEventContext
import com.example.biletflow.presentation.orderhistory.OrderHistoryViewModel
import com.example.biletflow.presentation.orderreview.TicketQrViewModel
import com.example.biletflow.presentation.support.*
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MockConsistencyTest {
    @Test fun inventoryIsReservedOnceAndReleasedAfterCancellation()=runTest {
        val client=createMockEventsHttpClient()
        try {
            approve(client);status(client,"publish")
            val type=client.post("http://test/events/101/ticket-type") {
                bearerAuth("mock-access-token");contentType(ContentType.Application.Json)
                setBody("""{"name":"One seat","price":0,"quantity":1,"max_per_order":1,"sales_start":"2026-01-01T10:00:00+05:00","sales_end":"2027-01-01T10:00:00+05:00"}""")
            }.body<TicketType>()
            val orders=OrdersRepositoryImpl(client,"http://test")
            val first=orders.createOrder(101,"First",listOf(CreateOrderItem(type.id,1)),"mock-access-token")
            assertEquals(409,assertFailsWith<OrdersApiException> {orders.createOrder(101,"Second",listOf(CreateOrderItem(type.id,1)),"mock-access-token")}.statusCode)
            orders.cancelOrder(first.id,"mock-access-token")
            val second=orders.createOrder(101,"Second",listOf(CreateOrderItem(type.id,1)),"mock-access-token")
            assertEquals("confirmed",second.status);assertNotEquals(first.id,second.id)
        } finally {client.close()}
    }
    private suspend fun approve(client:HttpClient) {
        assertEquals(200,client.post("http://test/organizers/apply") {bearerAuth("mock-access-token")}.status.value)
        repeat(3) {client.get("http://test/organizers/me") {bearerAuth("mock-access-token")}}
    }
    private suspend fun status(client:HttpClient,action:String)=client.patch("http://test/events/101/$action") {bearerAuth("mock-access-token")}
    private suspend fun patch(client:HttpClient,body:String)=client.patch("http://test/events/101") {bearerAuth("mock-access-token");contentType(ContentType.Application.Json);setBody(body)}
    @Test fun oneStateVisibilityTransitionsAndSeedBuyerOrderSurvivesUnavailableEvent()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(visitorCheckoutReady=true)
        val events=EventsRepositoryImpl(client,"http://test");val orders=OrdersRepositoryImpl(client,"http://test")
        val auth=AuthRepositoryImpl(client,"http://test");val buyer=auth.login("attendee@biletflow.kz","password123")
        val store=SessionStore().apply {save(buyer)}
        val requests=SessionOrdersRepository(orders,auth,store,backgroundScope)
        val history=OrderHistoryViewModel(requests,store);val qr=TicketQrViewModel(requests,store)
        try {
            val me=client.get("http://test/auth/me") {bearerAuth(buyer.accessToken)}.body<CurrentUser>()
            assertEquals(103,me.id);assertNull(me.organizer);assertFalse(me.isEventStaff);assertFalse(me.isPlatformAdmin)
            assertEquals(listOf(7110),orders.getMyOrders(buyer.accessToken).map {it.id})
            assertTrue(events.getEvents().none {it.id==101 || it.id==103})
            assertEquals(404,assertFailsWith<EventsApiException> {events.getEvent(101)}.statusCode)
            assertEquals(404,assertFailsWith<EventsApiException> {events.getTicketTypes(101)}.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> {orders.createOrder(101,"Buyer",listOf(CreateOrderItem(1002,1)),buyer.accessToken)}.statusCode)
            approve(client)
            assertEquals("draft",client.get("http://test/events/101/manage") {bearerAuth("mock-access-token")}.body<Event>().status)
            assertEquals(200,status(client,"publish").status.value)
            assertTrue(events.getEvents().any {it.id==101})
            patch(client,"""{"visibility":"unlisted"}""")
            assertTrue(events.getEvents().none {it.id==101});assertEquals(101,events.getEvent(101).id);assertTrue(events.getTicketTypes(101).isNotEmpty())
            patch(client,"""{"visibility":"private"}""")
            assertEquals(404,assertFailsWith<EventsApiException> {events.getEvent(101)}.statusCode)
            assertEquals(404,assertFailsWith<EventsApiException> {events.getTicketTypes(101)}.statusCode)
            patch(client,"""{"visibility":"public"}""")
            val order=orders.createOrder(101,"Seed buyer new registration",listOf(CreateOrderItem(1002,1)),buyer.accessToken)
            val ticket=order.tickets.single()
            assertEquals(200,status(client,"unpublish").status.value)
            assertTrue(loadEventContext(events,101).unavailable)
            history.open();history.state.first {!it.isLoading};assertTrue(history.state.value.orders.any {it.id==order.id})
            history.openOrder(order.id);history.state.first {it.openingOrderId==null};assertEquals(order.id,history.state.value.openedOrder?.id)
            qr.load(ticket.id);qr.state.first {!it.isLoading};assertNotNull(qr.state.value.pngBytes)
            assertEquals("confirmed",orders.getOrder(order.id,buyer.accessToken).status)
            assertEquals(404,assertFailsWith<OrdersApiException> {orders.createOrder(101,"Buyer",listOf(CreateOrderItem(1002,1)),buyer.accessToken)}.statusCode)
            assertEquals(200,status(client,"cancel").status.value)
            assertEquals("cancelled",orders.getOrder(order.id,buyer.accessToken).status)
            assertEquals("cancelled",client.get("http://test/events/101/manage") {bearerAuth("mock-access-token")}.body<Event>().status)
        } finally {history.viewModelScope.cancel();qr.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
    @Test fun suspensionAndPastCategoriesUsePublicRules()=runTest {
        val client=createMockEventsHttpClient(publishedFixtureEvents=true,seedSuspendedEventIds=setOf(101))
        val events=EventsRepositoryImpl(client,"http://test");val orders=OrdersRepositoryImpl(client,"http://test")
        try {
            assertTrue(events.getEvents().none {it.id==101})
            assertEquals(404,assertFailsWith<EventsApiException> {events.getEvent(101)}.statusCode)
            assertEquals(404,assertFailsWith<EventsApiException> {events.getTicketTypes(101)}.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> {orders.createOrder(101,"Buyer",listOf(CreateOrderItem(1002,1)),"mock-access-token")}.statusCode)
            approve(client)
            assertNotNull(client.get("http://test/events/101/manage") {bearerAuth("mock-access-token")}.body<Event>().suspendedAt)
        } finally {client.close()}
        val past=createMockEventsHttpClient()
        try {
            approve(past)
            assertEquals(200,patch(past,"""{"start_time":"2020-01-01T10:00:00Z","end_time":"2020-01-01T12:00:00Z","registration_opens_at":null,"registration_closes_at":null,"category":"Past only"}""").status.value)
            status(past,"publish")
            val repo=EventsRepositoryImpl(past,"http://test")
            assertTrue(repo.getEvents().none {it.id==101});assertFalse(repo.getCategories().contains("Past only"))
            val page=past.get("http://test/events") {parameter("include_past",true)}.body<EventPage>()
            assertTrue(page.items.any {it.id==101});assertEquals(101,repo.getEvent(101).id)
            assertEquals(409,assertFailsWith<OrdersApiException> {OrdersRepositoryImpl(past,"http://test").createOrder(101,"Late",listOf(CreateOrderItem(1002,1)),"mock-access-token")}.statusCode)
        } finally {past.close()}
    }
    @Test fun supportLengthCountsCodePointsAfterCompatibleTrim() {
        assertEquals("",supportTrim(" \t\n\u00A0\u001C\u0085"))
        assertEquals("Text",supportTrim("\u2003Text\u3000"))
        assertEquals(1,supportTextLength("😀"));assertEquals(5000,supportTextLength("😀".repeat(5000)))
        assertEquals(5001,supportTextLength("😀".repeat(5001)))
    }
    @Test fun bodyBoundaryIsCheckedAfterTrimInClientAndMock()=runTest {
        val client=createMockEventsHttpClient()
        val session=SessionStore().apply {save(AuthTokens("mock-access-token","mock-refresh-token","bearer"))}
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val cases=SupportCasesRepository(client,requests,"http://test")
        try {
            val case=cases.create(SupportCaseCreate(category="technical",subject="Length",message="First",eventId=102))
            val body="😀".repeat(5000)
            assertEquals(body,cases.send(case.id," \t$body\n").body)
            assertEquals(422,assertFailsWith<SupportApiException> {cases.send(case.id,body+"😀")}.statusCode)
            assertEquals(422,assertFailsWith<SupportApiException> {cases.send(case.id," \n\t\u0085")}.statusCode)
        } finally {client.close()}
    }
}
