package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.CreateOrderItem
import com.example.biletflow.domain.models.Order

interface OrdersRepository {
    suspend fun createOrder(
        eventId: Int,
        buyerName: String,
        items: List<CreateOrderItem>,
        accessToken: String,
    ): Order
}
