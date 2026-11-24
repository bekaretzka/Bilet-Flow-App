package com.example.biletflow

import com.example.biletflow.presentation.organizer.PaidSalesStatus
import com.example.biletflow.presentation.organizer.activationReadiness
import kotlin.test.*

class PaidSalesPresentationTest {
    @Test fun activeAndSuspendedSalesDoNotShowAnActivationFailure() {
        val data = PaidSalesStatus(500, "pending", 5000, "KZT", true, emptyMap(), false)
        assertEquals(false, data.activationReadiness())
        assertEquals(true, data.copy(canActivate = true).activationReadiness())
        assertEquals(false, data.copy(status = "not_started").activationReadiness())
        listOf("active", "suspended", "unknown").forEach { status ->
            assertNull(data.copy(status = status).activationReadiness())
            assertNull(data.copy(status = status, canActivate = true).activationReadiness())
        }
    }
}
