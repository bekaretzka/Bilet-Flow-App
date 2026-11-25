package com.example.biletflow.core.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { callback.value(null); return@rememberLauncherForActivityResult }
        val picked = runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            // Read at most 5 MB + 1 byte: enough to tell the server limit was exceeded without loading huge files.
            val bytes = resolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (total <= 5 * 1024 * 1024) {
                    val read = input.read(buffer); if (read < 0) break
                    out.write(buffer, 0, read); total += read
                }
                out.toByteArray()
            }
            val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            PickedImage(bytes, mime, "cover.$ext")
        }.getOrNull()
        callback.value(picked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
