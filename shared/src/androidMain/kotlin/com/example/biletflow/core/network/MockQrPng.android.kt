package com.example.biletflow.core.network

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

actual fun mockTicketQrPng(code: String): ByteArray {
    val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 320, 320,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
    return qrMatrixPng(matrix.width, matrix.height) { x, y -> matrix[x, y] }
}
