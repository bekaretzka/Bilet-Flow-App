package com.example.biletflow.core.network

import io.ktor.http.URLProtocol
import io.ktor.http.Url

expect val localApiBaseUrl: String
enum class ApiEnvironment { MOCK, LOCAL, STAGING }
data class NetworkConfiguration(val environment: ApiEnvironment, val baseUrl: String) {
    val sessionNamespace: String get() = environment.name.lowercase()+"-"+baseUrl.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
}
object NetworkSettings {
    var current = NetworkConfiguration(ApiEnvironment.MOCK,"http://mock.biletflow")
        private set
    fun configure(environment: String, localUrl: String = localApiBaseUrl, stagingUrl: String = ""): NetworkConfiguration {
        val mode=ApiEnvironment.entries.firstOrNull { it.name.equals(environment.trim(),true) }
            ?: error("Unknown environment: $environment (mock/local/staging)")
        val raw=when(mode) { ApiEnvironment.MOCK -> "http://mock.biletflow"; ApiEnvironment.LOCAL -> localUrl; ApiEnvironment.STAGING -> stagingUrl }
        require(raw.isNotBlank()) { "Staging URL is not configured. Set BILETFLOW_STAGING_URL / biletflow.stagingUrl to a confirmed HTTPS server." }
        val url=Url(raw.trim())
        require(url.host.isNotBlank() && (url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS))
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Base URL must not contain credentials, query or fragment" }
        require(mode != ApiEnvironment.STAGING || url.protocol == URLProtocol.HTTPS) { "Staging requires HTTPS" }
        return NetworkConfiguration(mode,raw.trim().trimEnd('/')).also { current=it }
    }
}
val apiBaseUrl: String get() = NetworkSettings.current.baseUrl
