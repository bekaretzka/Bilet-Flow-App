package com.example.biletflow

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(useMockEvents: Boolean = false) = ComposeUIViewController {
    App(useMockEvents = useMockEvents)
}
