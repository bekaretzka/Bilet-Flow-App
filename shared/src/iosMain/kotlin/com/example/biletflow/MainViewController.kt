package com.example.biletflow

import androidx.compose.ui.window.ComposeUIViewController
import com.example.biletflow.core.network.*

fun MainViewController(environment: String = "mock", localUrl: String = localApiBaseUrl, stagingUrl: String = "") = ComposeUIViewController {
    val config = androidx.compose.runtime.remember { runCatching { NetworkSettings.configure(environment,localUrl,stagingUrl) } }
    if(config.isFailure) androidx.compose.material3.Text(config.exceptionOrNull()?.message.orEmpty())
    else {
        val network=config.getOrThrow()
        App(useMockEvents = network.environment == ApiEnvironment.MOCK,sessionStorage = androidx.compose.runtime.remember { IosSessionStorage(network.sessionNamespace) })
    }
}