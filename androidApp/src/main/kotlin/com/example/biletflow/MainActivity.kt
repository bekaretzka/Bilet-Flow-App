package com.example.biletflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        var backAction: (() -> Unit)? = null
        val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { backAction?.invoke() }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        if (savedInstanceState == null) com.example.biletflow.core.links.IncomingLinks.deliver(intent?.dataString)

        val config = runCatching { com.example.biletflow.core.network.NetworkSettings.configure(
            BuildConfig.API_ENVIRONMENT, BuildConfig.API_LOCAL_URL, BuildConfig.API_STAGING_URL) }
        setContent {
            if(config.isFailure) {
                NetworkConfigurationError(config.exceptionOrNull()?.message.orEmpty())
                return@setContent
            }
            val network = config.getOrThrow()
            App(useMockEvents = network.environment == com.example.biletflow.core.network.ApiEnvironment.MOCK, sessionStorage = androidx.compose.runtime.remember {
                AndroidSessionStorage(applicationContext, network.sessionNamespace)
            }, registerBack = { action ->
                backAction = action
                backCallback.isEnabled = action != null
            })
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(useMockEvents = true)
}
