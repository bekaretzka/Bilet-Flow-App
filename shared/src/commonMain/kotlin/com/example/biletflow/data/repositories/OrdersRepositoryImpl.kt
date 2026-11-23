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
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

        return response.asOrderOrThrow(expectedStatus = 201)
    }

    override suspend fun payOrder(orderId: Int, accessToken: String): Order {
        val response = client.post("$baseUrl/orders/$orderId/pay") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        return response.asOrderOrThrow(expectedStatus = 200)
    }

    override suspend fun cancelOrder(orderId: Int, accessToken: String): Order {
        val response = client.post("$baseUrl/orders/$orderId/cancel") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        return response.asOrderOrThrow(expectedStatus = 200)
    }

    override suspend fun getOrder(orderId: Int, accessToken: String): Order {
        val response = client.get("$baseUrl/orders/$orderId") {
            bearerAuth(accessToken)
        }
        return response.asOrderOrThrow(expectedStatus = 200)
    }

    override suspend fun getMyOrders(accessToken: String): List<Order> {
        val response = client.get("$baseUrl/orders/me") {
            bearerAuth(accessToken)
        }
        if (response.status.value == 200) return response.body()
        response.asOrderOrThrow(expectedStatus = 200)
        error("Unreachable")
    }

    override suspend fun getTicketQr(ticketId: Int, accessToken: String): ByteArray {
        val response = client.get("$baseUrl/tickets/$ticketId/qr") {
            bearerAuth(accessToken)
        }
        if (response.status.value == 200) {
            val contentType = response.headers[io.ktor.http.HttpHeaders.ContentType].orEmpty()
            if (!contentType.startsWith("image/png", ignoreCase = true)) {
                throw OrdersApiException(502, null, "Ticket QR response is not a PNG")
            }
            return response.body()
        }
        response.asOrderOrThrow(expectedStatus = 200)
        error("Unreachable")
    }

    private suspend fun io.ktor.client.statement.HttpResponse.asOrderOrThrow(
        expectedStatus: Int,
    ): Order {
        if (status.value == expectedStatus) return body()
        val body = bodyAsText()
        val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        throw OrdersApiException(
            statusCode = status.value,
            errorCode = payload?.get("error")?.jsonPrimitive?.contentOrNull,
            message = payload?.get("message")?.jsonPrimitive?.contentOrNull
                ?: payload?.get("detail")?.jsonPrimitive?.contentOrNull
                ?: "Order request failed (${status.value})",
        )
    }
}
