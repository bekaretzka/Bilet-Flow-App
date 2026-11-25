package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PaidSalesTest {
    @Test fun checklistRetryAccessAndRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient(publishedFixtureEvents = true)
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val vm = PaidSalesViewModel(client, requests, session, "http://test")
        try {
            runCurrent()
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertEquals("approved", organizer.state.value.profile?.status)
            vm.open(101, "First"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.error)
            vm.reload(); vm.state.first { !it.loading }
            assertEquals("not_started", vm.state.value.data?.status)
            assertFalse(vm.state.value.data!!.checklist.getValue("fee_paid"))
            assertTrue(vm.state.value.data!!.checklist.getValue("has_paid_ticket_type"))
            vm.open(102, "Second")
            assertNull(vm.state.value.data)
            vm.state.first { !it.loading }
            assertEquals(102, vm.state.value.data?.eventId)
            assertEquals("pending", vm.state.value.data?.status)
            assertTrue(vm.state.value.data!!.checklist.values.any { it })
            assertTrue(vm.state.value.data!!.checklist.values.any { !it })
            vm.open(103, "Third"); vm.state.first { !it.loading }
            assertEquals("active", vm.state.value.data?.status)
            assertEquals(5000L, vm.state.value.data?.feeAmount)
            assertTrue(vm.state.value.data!!.checklist.values.all { it })
            for (title in listOf("paid-sales-missing", "paid-sales-suspended")) {
                create.open(); create.update("title", title)
                create.update("start_time", "2027-02-01T10:00:00+05:00")
                create.update("end_time", "2027-02-01T12:00:00+05:00")
                create.save(); create.state.first { !it.saving }
                vm.open(requireNotNull(create.state.value.created).id, title)
                vm.state.first { !it.loading }
                if (title.endsWith("missing")) {
                    assertTrue(vm.state.value.missing); assertFalse(vm.state.value.error)
                } else {
                    assertEquals("suspended", vm.state.value.data?.status)
                    assertNotNull(vm.state.value.data?.suspensionReason)
                }
                create.reset()
            }
            session.save(AuthTokens("expired-access", "mock-refresh-token", "bearer")); runCurrent()
            vm.open(102, "Refresh"); vm.state.first { !it.loading }
            assertEquals("pending", vm.state.value.data?.status)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            assertNull(vm.state.value.eventId)
            vm.open(101, "Foreign"); vm.state.first { !it.loading }
            assertTrue(vm.state.value.denied)
            assertNull(vm.state.value.data)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); create.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun lateResponseCannotRestoreOldEventOrSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val releaseSession = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.removePrefix("/events/").substringBefore('/').toInt()
            if (id == 101) release.await()
            if (id == 103) releaseSession.await()
            respond("""{"event_id":$id,"status":"not_started","fee_amount":5000,"currency":"KZT","is_simulated":true,"checklist":{},"can_activate":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = PaidSalesViewModel(client, requests, session, "http://test")
        try {
            runCurrent()
            vm.open(101, "Old"); runCurrent()
            vm.open(102, "New"); vm.state.first { !it.loading }
            release.complete(Unit); runCurrent()
            assertEquals(102, vm.state.value.data?.eventId)
            vm.open(103, "Old account"); runCurrent()
            session.clear(); runCurrent()
            releaseSession.complete(Unit); runCurrent()
            assertNull(vm.state.value.eventId)
            assertNull(vm.state.value.data)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
