@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.example.biletflow.core.network

import kotlinx.cinterop.*
import platform.CoreImage.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*
import platform.posix.memcpy

actual fun mockTicketQrPng(code: String): ByteArray {
    val filter = requireNotNull(CIFilter.filterWithName("CIQRCodeGenerator"))
    val encoded = code.encodeToByteArray()
    val message = encoded.usePinned {
        NSData.create(bytes = if (encoded.isEmpty()) null else it.addressOf(0), length = encoded.size.toULong())
    }
    filter.setValue(message, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val image = requireNotNull(filter.outputImage).imageByApplyingTransform(CGAffineTransformMakeScale(8.0, 8.0))
    val background = CIImage.imageWithColor(CIColor.colorWithRed(1.0, 1.0, 1.0)).imageByCroppingToRect(CGRectInset(image.extent, -32.0, -32.0))
    val padded = image.imageByCompositingOverImage(background)
    val cgImage = requireNotNull(CIContext.contextWithOptions(null).createCGImage(padded, padded.extent))
    val data = try { requireNotNull(UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage))) } finally { CGImageRelease(cgImage) }
    return ByteArray(data.length.toInt()).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
}
