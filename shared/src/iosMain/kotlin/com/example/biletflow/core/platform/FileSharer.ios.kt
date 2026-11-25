@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package com.example.biletflow.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

// Not compiled on Windows: written against the Kotlin/Native UIKit bindings, verify on macOS.
@Composable
actual fun rememberFileSharer(): FileSharer = remember {
    FileSharer { bytes, fileName, _ ->
        runCatching {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + safeName)
            val data = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
            check(data.writeToURL(url, atomically = true))
            val controller = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
            val root = requireNotNull(UIApplication.sharedApplication.keyWindow?.rootViewController)
            var top = root
            while (top.presentedViewController != null) top = requireNotNull(top.presentedViewController)
            top.presentViewController(controller, animated = true, completion = null)
        }.isSuccess
    }
}
