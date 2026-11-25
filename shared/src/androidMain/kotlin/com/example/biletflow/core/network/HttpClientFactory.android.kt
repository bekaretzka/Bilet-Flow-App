package com.example.biletflow.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    installSessionErrorValidation()
    install(HttpTimeout) {
        requestTimeoutMillis = 75_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 75_000
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
