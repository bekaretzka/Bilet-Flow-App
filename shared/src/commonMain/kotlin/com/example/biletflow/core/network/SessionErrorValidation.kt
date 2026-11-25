package com.example.biletflow.core.network

import com.example.biletflow.data.repositories.OrdersApiException
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.*

// Preserve suspension even when a legacy screen discards the server's error body.
fun HttpClientConfig<*>.installSessionErrorValidation() {
    HttpResponseValidator {
        validateResponse { response ->
            if(response.status.value == 403 && response.call.request.headers[HttpHeaders.Authorization] != null) {
                val body=runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                if((body?.get("error") as? JsonPrimitive)?.contentOrNull == "UserSuspendedError")
                    throw OrdersApiException(403,"UserSuspendedError","Account suspended")
            }
        }
    }
}
