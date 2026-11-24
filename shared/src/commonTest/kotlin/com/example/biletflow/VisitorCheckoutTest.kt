package com.example.biletflow

import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.AuthRepositoryImpl
import com.example.biletflow.data.repositories.OrdersApiException
import com.example.biletflow.data.repositories.OrdersRepositoryImpl
import com.example.biletflow.domain.models.CreateOrderItem
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VisitorCheckoutTest {
    @Test fun registeredBuyerCanPayReadQrAndCancelWithAccountIsolation() = runTest {
        val client = createMockEventsHttpClient(visitorCheckoutReady = true)
        val auth = AuthRepositoryImpl(client, "http://test")
        val orders = OrdersRepositoryImpl(client, "http://test")
        try {
            auth.register("  New@Example.kz ", "password123")
            val login = auth.login("new@example.kz", "password123")
            assertTrue(orders.getMyOrders(login.accessToken).isEmpty())
            val pending = orders.createOrder(101, "New Buyer", listOf(CreateOrderItem(1001, 1), CreateOrderItem(1002, 1)), login.accessToken)
            assertEquals("pending", pending.status)
            assertEquals(5000L, pending.totalAmount)
            assertEquals("new@example.kz", pending.buyerEmail)
            val rotated = auth.refresh(login.refreshToken)
            val paid = orders.payOrder(pending.id, rotated.accessToken)
            assertEquals("confirmed", paid.status)
            assertEquals(2, paid.tickets.size)
            assertTrue(orders.getTicketQr(paid.tickets.first().id, rotated.accessToken).size > 8)
            assertFails { orders.getTicketQr(paid.tickets.last().id, rotated.accessToken) } // Explicit retry fixture.
            assertTrue(orders.getTicketQr(paid.tickets.last().id, rotated.accessToken).size > 8)
            assertEquals(paid, orders.getOrder(paid.id, rotated.accessToken))
            assertEquals(paid, orders.getMyOrders(rotated.accessToken).single())

            auth.register("second@example.kz", "password123")
            val second = auth.login("second@example.kz", "password123")
            assertTrue(orders.getMyOrders(second.accessToken).isEmpty())
            assertEquals(404, assertFailsWith<OrdersApiException> { orders.getOrder(paid.id, second.accessToken) }.statusCode)
            assertEquals(404, assertFailsWith<OrdersApiException> { orders.payOrder(paid.id, second.accessToken) }.statusCode)
            assertEquals(404, assertFailsWith<OrdersApiException> { orders.cancelOrder(paid.id, second.accessToken) }.statusCode)
            assertEquals(404, assertFailsWith<OrdersApiException> { orders.getTicketQr(paid.tickets.first().id, second.accessToken) }.statusCode)
            assertFalse(orders.getMyOrders("mock-access-token").any { it.id == paid.id })
            val free = orders.createOrder(101, "Second Buyer", listOf(CreateOrderItem(1002, 1)), second.accessToken)
            assertNotEquals(paid.id, free.id)
            assertEquals("confirmed", free.status)
            assertEquals(0L, free.totalAmount)
            assertEquals("cancelled", orders.cancelOrder(free.id, second.accessToken).status)
            assertEquals("cancelled", orders.getMyOrders(second.accessToken).single().status)
            assertEquals("confirmed", orders.getMyOrders(rotated.accessToken).single().status)
        } finally { client.close() }
    }
}
