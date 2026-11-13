package com.example.biletflow.data.repositories

import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.core.network.createHttpClient
import com.example.biletflow.domain.models.CreateOrderItem
import com.example.biletflow.domain.models.CreateOrderRequest
import com.example.biletflow.domain.models.Order
import com.example.biletflow.domain.repositories.OrdersRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OrdersApiException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String,
) : Exception(message)

class OrdersRepositoryImpl(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = apiBaseUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OrdersRepository {
    override suspend fun createOrder(
        eventId: Int,
        buyerName: String,
        items: List<CreateOrderItem>,
        accessToken: String,
    ): Order {
        val response = client.post("$baseUrl/events/$eventId/orders") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateOrderRequest(buyerName = buyerName, items = items))
        }
        if (response.status.value == 201) return response.body()

        val body = response.bodyAsText()
        val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        throw OrdersApiException(
            statusCode = response.status.value,
            errorCode = payload?.get("error")?.jsonPrimitive?.contentOrNull,
            message = payload?.get("message")?.jsonPrimitive?.contentOrNull
                ?: payload?.get("detail")?.jsonPrimitive?.contentOrNull
                ?: "Order request failed (${response.status.value})",
        )
    }
}
