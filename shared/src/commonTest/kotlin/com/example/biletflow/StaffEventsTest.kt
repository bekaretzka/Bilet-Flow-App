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
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StaffEventsTest {
    @Test fun assignedAndOwnedEventsRevocationRetryAndRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient(publishedFixtureEvents = true)
        val auth = AuthRepositoryImpl(client, "http://test")
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), auth, session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val staff = EventStaffViewModel(client, requests, session, "http://test")
        val scanner = StaffEventsViewModel(client, requests, session, "http://test")
        try {
            runCurrent()
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            val events = listOf("published", "draft", "cancelled").mapIndexed { index, status ->
                create.open(); create.update("title", "Scanner $status")
                create.update("start_time", "2027-02-0${3 - index}T10:00:00+05:00")
                create.update("end_time", "2027-02-0${3 - index}T12:00:00+05:00")
                create.update("venue_name", "Test Hall"); create.update("venue_address", "Test Address")
                create.save(); create.state.first { !it.saving }
                val event = requireNotNull(create.state.value.created)
                create.reset()
                if (status != "draft") assertEquals(200, client.patch("http://test/events/${event.id}/${if (status == "published") "publish" else "cancel"}") {
                    bearerAuth("mock-access-token")
                }.status.value)
                staff.open(event.id, event.title); staff.state.first { !it.loading }
                staff.email(" EMPTY@BILETFLOW.KZ "); staff.add(); staff.state.first { !it.busy && !it.loading }
                assertEquals(101, staff.state.value.staff.single().userId)
                event.id
            }
            // Both ownership and assignment grant access, but produce only one row.
            staff.open(events[0], "Owner"); staff.state.first { !it.loading }
            staff.email("visitor@biletflow.kz"); staff.add(); staff.state.first { !it.busy && !it.loading }
            scanner.open(); scanner.state.first { !it.loading }; assertTrue(scanner.state.value.error)
            scanner.reload(); scanner.state.first { !it.loading }
            assertFalse(scanner.state.value.error)
            assertEquals(1, scanner.state.value.events.count { it.id == events[0] })
            assertFalse(scanner.state.value.events.any { it.status == "draft" })
            assertTrue(scanner.state.value.events.any { it.id == events[2] && it.status == "cancelled" })
            assertEquals(204, client.delete("http://test/events/${events[0]}/staff/100") { bearerAuth("mock-access-token") }.status.value)
            scanner.reload(); scanner.state.first { !it.loading }
            assertTrue(scanner.state.value.events.any { it.id == events[0] })

            // Login as a normal user who has no organizer profile.
            session.save(auth.login("empty@biletflow.kz", "password123")); runCurrent()
            assertFalse(scanner.state.value.open); assertTrue(scanner.state.value.events.isEmpty())
            scanner.open(); scanner.state.first { !it.loading }
            assertEquals(setOf(events[0], events[2]), scanner.state.value.events.map { it.id }.toSet())
            val times = scanner.state.value.events.map { Instant.parse(it.startTime) }
            assertEquals(times.sorted(), times)
            session.rotateTokens(AuthTokens("expired-access", "mock-empty-refresh-token", "bearer"))
            scanner.reload(); scanner.state.first { !it.loading }
            assertFalse(scanner.state.value.error)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            // Removal on another device is reflected on the next request (also the foreground refresh path).
            for (id in events) assertEquals(204, client.delete("http://test/events/$id/staff/101") { bearerAuth("mock-access-token") }.status.value)
            scanner.reload(); scanner.state.first { !it.loading }
            assertTrue(scanner.state.value.events.isEmpty())
            scanner.close(); assertFalse(scanner.state.value.open)
            scanner.open(); scanner.state.first { !it.loading }; assertTrue(scanner.state.value.events.isEmpty())
        } finally {
            scanner.viewModelScope.cancel(); staff.viewModelScope.cancel(); organizer.viewModelScope.cancel(); create.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun lateResponseDoesNotRestorePreviousAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val oldAccount = request.headers[HttpHeaders.Authorization] == "Bearer old"
            if (oldAccount) gate.await()
            val body = if (oldAccount) """[{"id":999,"organizer_id":31,"title":"Previous account event","start_time":"2027-01-01T10:00:00+05:00","end_time":"2027-01-01T12:00:00+05:00","status":"published","visibility":"private"}]""" else "[]"
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("old", "old-refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val scanner = StaffEventsViewModel(client, requests, session, "http://test")
        try {
            runCurrent(); scanner.open(); runCurrent(); assertTrue(scanner.state.value.loading)
            session.clear(); session.save(AuthTokens("new", "new-refresh", "bearer")); runCurrent()
            assertFalse(scanner.state.value.open)
            scanner.open(); scanner.state.first { !it.loading }
            gate.complete(Unit); runCurrent()
            assertTrue(scanner.state.value.open); assertFalse(scanner.state.value.loading)
            assertFalse(scanner.state.value.error); assertTrue(scanner.state.value.events.isEmpty())
        } finally { scanner.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
