package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EventStaffTest {
    @Test fun assignmentsErrorsRecoveryRefreshAndOwnership() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var posts = 0
        val deletions = mutableListOf<String>()
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.build().encodedPath
            if (path.endsWith("/staff") && request.method == HttpMethod.Post) posts++
            if ("/staff/" in path && request.method == HttpMethod.Delete) deletions.add(path)
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val vm = EventStaffViewModel(client, requests, session, "http://test")
        suspend fun settled() { vm.state.first { !it.busy && !it.loading } }
        try {
            runCurrent()
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            vm.open(101, "First"); settled(); assertTrue(vm.state.value.error)
            vm.reload(); settled(); assertTrue(vm.state.value.loaded); assertTrue(vm.state.value.staff.isEmpty())
            vm.email("not an email"); vm.add(); assertEquals("invalid", vm.state.value.emailError)
            assertEquals(0, posts)
            vm.email("missing@biletflow.kz"); vm.add(); settled()
            assertEquals("UserNotFoundError", vm.state.value.actionError)
            vm.email("field-error@biletflow.kz"); vm.add(); settled()
            assertNotNull(vm.state.value.emailError)
            vm.email(" EMPTY@BILETFLOW.KZ ")
            val before = posts
            vm.add(); vm.add(); settled()
            assertEquals(before + 1, posts)
            val staff = vm.state.value.staff.single()
            assertEquals("empty@biletflow.kz", staff.email)
            assertEquals(101, staff.userId)
            assertEquals("", vm.state.value.email)
            vm.email("empty@biletflow.kz"); vm.add(); settled()
            assertEquals("StaffAlreadyAssignedError", vm.state.value.actionError)
            assertEquals(1, vm.state.value.staff.size)
            vm.askRemove(staff); vm.dismissRemove(); vm.confirmRemove(); runCurrent()
            assertTrue(deletions.isEmpty())
            vm.askRemove(staff); vm.confirmRemove(); vm.confirmRemove(); settled()
            assertEquals(listOf("/events/101/staff/101"), deletions)
            assertTrue(vm.state.value.staff.isEmpty()); assertNull(vm.state.value.actionError)
            vm.email("empty@biletflow.kz"); vm.add(); settled()
            vm.open(102, "Other"); assertTrue(vm.state.value.staff.isEmpty()); assertEquals("", vm.state.value.email)
            settled(); assertTrue(vm.state.value.staff.isEmpty())
            vm.open(101, "First"); settled(); assertEquals(1, vm.state.value.staff.size)
            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            vm.reload(); settled()
            assertFalse(vm.state.value.error)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))

            create.open(); create.update("title", "staff-unknown")
            create.update("start_time", "2027-02-01T10:00:00+05:00"); create.update("end_time", "2027-02-01T12:00:00+05:00")
            create.save(); create.state.first { !it.saving }
            val id = requireNotNull(create.state.value.created).id
            vm.open(id, "Unknown result"); settled()
            vm.email("empty@biletflow.kz"); vm.add(); settled(); assertTrue(vm.state.value.unknown)
            val afterUnknown = posts
            vm.add(); runCurrent(); assertEquals(afterUnknown, posts)
            vm.reload(); settled(); assertFalse(vm.state.value.unknown)
            val saved = vm.state.value.staff.single()
            vm.askRemove(saved); vm.confirmRemove(); settled(); assertTrue(vm.state.value.unknown)
            val deleteCount = deletions.size
            vm.askRemove(saved); vm.confirmRemove(); runCurrent(); assertEquals(deleteCount, deletions.size)
            vm.reload(); settled(); assertTrue(vm.state.value.staff.isEmpty()); assertFalse(vm.state.value.unknown)
            session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            assertNull(vm.state.value.eventId); assertEquals("", vm.state.value.email)
            vm.open(101, "Foreign"); settled(); assertTrue(vm.state.value.denied)
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            create.open(); create.update("title", "Other owner")
            create.update("start_time", "2027-02-01T10:00:00+05:00"); create.update("end_time", "2027-02-01T12:00:00+05:00")
            create.save(); create.state.first { !it.saving }
            vm.open(requireNotNull(create.state.value.created).id, "Own empty event"); settled()
            assertTrue(vm.state.value.staff.isEmpty()); assertFalse(vm.state.value.denied)
            AuthRepositoryImpl(client, "http://test").register("new-staff@biletflow.kz", "password123")
            vm.email(" NEW-STAFF@BILETFLOW.KZ "); vm.add(); settled()
            assertEquals(1000, vm.state.value.staff.single().userId)
            assertEquals("new-staff@biletflow.kz", vm.state.value.staff.single().email)
        } finally {
            vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); create.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun staleListAndMutationResponsesAreIgnored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val listGate = CompletableDeferred<Unit>()
        val mutationGate = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.removePrefix("/events/").substringBefore('/').toInt()
            if (request.method == HttpMethod.Get) {
                if (id == 101) listGate.await()
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                mutationGate.await()
                respond("""{"user_id":200,"email":"empty@biletflow.kz","created_at":"2026-11-23T10:00:00+05:00"}""",
                    HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = EventStaffViewModel(client, requests, session, "http://test")
        try {
            runCurrent(); vm.open(101, "Old"); runCurrent()
            vm.open(102, "New"); vm.state.first { !it.loading }
            listGate.complete(Unit); runCurrent(); assertEquals(102, vm.state.value.eventId)
            vm.email("empty@biletflow.kz"); vm.add(); runCurrent(); assertTrue(vm.state.value.busy)
            session.clear(); session.save(AuthTokens("other", "other-refresh", "bearer")); runCurrent()
            mutationGate.complete(Unit); runCurrent()
            assertNull(vm.state.value.eventId); assertTrue(vm.state.value.staff.isEmpty()); assertEquals("", vm.state.value.email)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
