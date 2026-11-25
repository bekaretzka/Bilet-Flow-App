package com.example.biletflow.core.platform

import androidx.compose.runtime.Composable

class PickedImage(val bytes: ByteArray, val mimeType: String, val fileName: String)

/** Android: Photo Picker (PickVisualMedia, images only). iOS: PHPickerViewController. Returns a launcher; null result = cancelled or unreadable. */
@Composable
expect fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit
