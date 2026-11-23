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

        setContent {
            App(useMockEvents = BuildConfig.DEBUG, sessionStorage = androidx.compose.runtime.remember {
                AndroidSessionStorage(applicationContext, if (BuildConfig.DEBUG) "mock" else "api")
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
