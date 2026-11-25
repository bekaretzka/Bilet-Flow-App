package com.example.biletflow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.biletflow.core.links.IncomingLinks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        var backAction: (() -> Unit)? = null
        val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { backAction?.invoke() }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        // Only the first creation takes the link: a restore (rotation, process recreation) keeps the same intent.
        if (savedInstanceState == null) deliverLink(intent)

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
            }, webHost = BuildConfig.WEB_HOST, registerBack = { action ->
                backAction = action
                backCallback.isEnabled = action != null
            })
        }
    }

    /** launchMode=singleTask: a link while the app runs arrives here instead of starting a second instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverLink(intent)
    }

    private fun deliverLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // reopening from Recents replays the original intent; that is not a new transition
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        IncomingLinks.deliver(intent.dataString)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(useMockEvents = true)
}
