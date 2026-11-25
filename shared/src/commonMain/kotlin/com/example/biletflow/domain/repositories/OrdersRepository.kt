package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.CreateOrderItem
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.models.OrderPreview

interface OrdersRepository {
    suspend fun createOrder(
        eventId: Int,
        buyerName: String,
        items: List<CreateOrderItem>,
        accessToken: String,
        promoCode: String? = null,
    ): Order

    /** POST /events/{id}/orders/preview: server prices, nothing is reserved. */
    suspend fun previewOrder(eventId: Int, items: List<CreateOrderItem>, promoCode: String?, accessToken: String): OrderPreview

    suspend fun payOrder(orderId: Int, accessToken: String): Order

    suspend fun cancelOrder(orderId: Int, accessToken: String): Order
    suspend fun refundOrder(orderId: Int, reason: String, accessToken: String): Order

    suspend fun getOrder(orderId: Int, accessToken: String): Order

    suspend fun getMyOrders(accessToken: String): List<Order>

    suspend fun getTicketQr(ticketId: Int, accessToken: String): ByteArray
}
