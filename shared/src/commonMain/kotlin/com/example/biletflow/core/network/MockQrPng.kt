package com.example.biletflow.core.network

/** Mock HTTP response only. Real ticket PNGs always come from the backend. */
expect fun mockTicketQrPng(code: String): ByteArray

/** Grayscale PNG with uncompressed zlib blocks; avoids Android framework APIs in host tests. */
internal fun qrMatrixPng(width: Int, height: Int, dark: (Int, Int) -> Boolean): ByteArray {
    val raw = ByteArray((width + 1) * height)
    for (y in 0 until height) for (x in 0 until width) raw[y * (width + 1) + x + 1] = if (dark(x, y)) 0 else -1
    val zlib = mutableListOf<Byte>(0x78, 0x01)
    var offset = 0
    while (offset < raw.size) {
        val length = minOf(65535, raw.size - offset)
        zlib.add(if (offset + length == raw.size) 1 else 0)
        zlib.add(length.toByte()); zlib.add((length shr 8).toByte())
        zlib.add(length.inv().toByte()); zlib.add((length.inv() shr 8).toByte())
        for (i in offset until offset + length) zlib.add(raw[i])
        offset += length
    }
    var a = 1; var b = 0
    raw.forEach { a = (a + (it.toInt() and 255)) % 65521; b = (b + a) % 65521 }
    fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    zlib.addAll(intBytes((b shl 16) or a).toList())
    val png = mutableListOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)
    fun chunk(type: String, data: ByteArray) {
        val bytes = type.encodeToByteArray() + data
        var crc = -1
        bytes.forEach { byte ->
            crc = crc xor (byte.toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor -306674912 else crc ushr 1 }
        }
        png.addAll(intBytes(data.size).toList()); png.addAll(bytes.toList()); png.addAll(intBytes(crc.inv()).toList())
    }
    chunk("IHDR", intBytes(width) + intBytes(height) + byteArrayOf(8, 0, 0, 0, 0))
    chunk("IDAT", zlib.toByteArray()); chunk("IEND", byteArrayOf())
    return png.toByteArray()
}
