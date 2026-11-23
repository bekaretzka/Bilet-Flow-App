@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.example.biletflow.presentation.organizer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import platform.UIKit.UIViewController

/** Installed by the iOS application before creating the Compose root. All callbacks run on main. */
class IosCameraHandle(val controller: UIViewController, val requestAccess: () -> Unit, val openSettings: () -> Unit, val stop: () -> Unit)
object IosCameraBridge {
    var create: (((List<String>) -> Unit, (Int) -> Unit) -> IosCameraHandle)? = null
}

@Composable
actual fun CameraQrPreview(modifier: Modifier, onFrame: (List<String>) -> Unit) {
    val callback by rememberUpdatedState(onFrame)
    var status by remember { mutableIntStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    val handle = remember { IosCameraBridge.create?.invoke({ if (alive) callback(it) }, { if (alive) status = it }) }
    DisposableEffect(handle) { onDispose { alive = false; handle?.stop?.invoke() } }
    LaunchedEffect(handle) { if (handle == null) status = 4 else handle.requestAccess() }
    Box(modifier) {
        if (handle != null) UIKitViewController(factory = { handle.controller }, modifier = Modifier.fillMaxSize())
        if (status != 0) Surface { CameraStatusPanel(status, { handle?.requestAccess?.invoke() }, { handle?.openSettings?.invoke() }) }
    }
}
