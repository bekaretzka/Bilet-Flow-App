package com.example.biletflow.data.repositories

import com.example.biletflow.core.network.apiBaseUrl
import com.example.biletflow.core.network.createHttpClient
import com.example.biletflow.domain.repositories.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class AuthRequest(
    val email: String,
    val password: String,
)

class AuthApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

class AuthRepositoryImpl(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = apiBaseUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AuthRepository {

    override suspend fun login(email: String, password: String) {
        execute("/auth/login", email, password)
    }

    override suspend fun register(email: String, password: String) {
        execute("/auth/register", email, password)
    }

    private suspend fun execute(path: String, email: String, password: String) {
        val response = client.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email = email, password = password))
        }

        if (response.status.value in 200..299) return

        val body = response.bodyAsText()
        throw AuthApiException(
            statusCode = response.status.value,
            message = parseErrorMessage(body) ?: "Request failed (${response.status.value})",
        )
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null

        return runCatching {
            val detail = json.parseToJsonElement(body).jsonObject["detail"]
            when (detail) {
                is JsonArray -> detail
                    .mapNotNull { item -> item.jsonObject["msg"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n")
                    .ifBlank { null }
                is JsonPrimitive -> detail.contentOrNull
                is JsonObject -> detail["msg"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.getOrNull()
    }
}
