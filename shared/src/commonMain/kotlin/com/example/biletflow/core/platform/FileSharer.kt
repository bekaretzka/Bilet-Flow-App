package com.example.biletflow.core.platform

import androidx.compose.runtime.Composable

/** Saves bytes to the app's temporary folder and opens the system open/share UI. Returns false when nothing could be shown. */
fun interface FileSharer {
    fun share(bytes: ByteArray, fileName: String, mimeType: String): Boolean
}

/** Android: cache dir + FileProvider + system chooser. iOS: NSTemporaryDirectory + share sheet. */
@Composable
expect fun rememberFileSharer(): FileSharer
