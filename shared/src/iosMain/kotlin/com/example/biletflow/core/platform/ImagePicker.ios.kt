@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package com.example.biletflow.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

// Not compiled on Windows: written against the Kotlin/Native PhotosUI bindings, verify on macOS.
private class PickerDelegate(val onResult: (PickedImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: run { onResult(null); return }
        val provider = result.itemProvider
        val types = listOf("public.png" to "image/png", "org.webmproject.webp" to "image/webp", "public.jpeg" to "image/jpeg")
        val (uti, mime) = types.firstOrNull { provider.hasItemConformingToTypeIdentifier(it.first) } ?: ("public.jpeg" to "image/jpeg")
        provider.loadDataRepresentationForTypeIdentifier(uti) { data: NSData?, _ ->
            val bytes = data?.let { d -> ByteArray(d.length.toInt()).also { array -> if (array.isNotEmpty()) array.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) } } }
            dispatch_async(dispatch_get_main_queue()) {
                onResult(bytes?.let { PickedImage(it, mime, "cover." + mime.substringAfter('/').replace("jpeg", "jpg")) })
            }
        }
    }
}

@Composable
actual fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val delegate = remember { PickerDelegate { callback.value(it) } }
    return remember {
        {
            val configuration = PHPickerConfiguration().apply { filter = PHPickerFilter.imagesFilter; selectionLimit = 1 }
            val picker = PHPickerViewController(configuration).apply { this.delegate = delegate }
            var top = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (top?.presentedViewController != null) top = top.presentedViewController
            top?.presentViewController(picker, animated = true, completion = null)
        }
    }
}
