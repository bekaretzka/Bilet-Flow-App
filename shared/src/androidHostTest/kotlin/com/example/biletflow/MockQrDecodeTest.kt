package com.example.biletflow

import com.example.biletflow.core.network.*
import com.example.biletflow.data.repositories.OrdersRepositoryImpl
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MockQrDecodeTest {
    private fun decode(png: ByteArray): String {
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertTrue(image.width >= 200)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text
    }
    @Test fun generatedPngPreservesRawText() {
        assertEquals(" Raw+Код ? ", decode(mockTicketQrPng(" Raw+Код ? ")))
    }
    @Test fun mockEndpointReturnsQrForEachIssuedTicket() = runTest {
        val client = createMockEventsHttpClient()
        try {
            val orders = OrdersRepositoryImpl(client, "http://test")
            val free = orders.getOrder(7102, "mock-access-token").tickets.single()
            assertEquals(free.code, decode(orders.getTicketQr(free.id, "mock-access-token")))
            val paid = orders.payOrder(7101, "mock-access-token").tickets.single()
            assertEquals(paid.code, decode(orders.getTicketQr(paid.id, "mock-access-token")))
            assertNotEquals(free.code, paid.code)
        } finally { client.close() }
    }
}
