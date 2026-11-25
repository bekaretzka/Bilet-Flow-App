package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizerTicketTypesTest {
    @Test fun manageSuspensionClearsSessionAndPersistentRecord() = runTest {
        var record: String? = null
        val storage = object : com.example.biletflow.domain.session.SessionStorage {
            override suspend fun read() = record
            override suspend fun write(value: String) { record = value }
            override suspend fun clear() { record = null }
        }
        val client = createMockEventsHttpClient()
        try {
            val auth = AuthRepositoryImpl(client,"http://test")
            auth.register("suspend-on-manage@biletflow.kz","password123")
            val store = SessionStore(storage,this)
            store.save(auth.login("suspend-on-manage@biletflow.kz","password123"))
            advanceUntilIdle(); assertNotNull(record)
            val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
            val repo = OrganizerTicketTypesRepository(client,requests,"http://test")
            val error = assertFailsWith<OrdersApiException> { repo.list(103) }
            assertEquals("UserSuspendedError",error.errorCode)
            assertTrue(store.suspended.value); assertNull(store.tokens.value)
            advanceUntilIdle(); assertNull(record)
        } finally { client.close() }
    }

    @Test fun fullManageListFreshViewModelMutationsRecoveryAndAccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var lose: String? = null
        val writes = mutableMapOf<String, Int>()
        val managePaths = mutableListOf<String>()
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.build().encodedPath
            assertFalse(path.startsWith("/organizer/") && path.contains("ticket-types"))
            if (path.endsWith("/ticket-types/manage")) {
                assertTrue(request.url.parameters.isEmpty())
                managePaths.add(path)
            }
            if (request.method in listOf(HttpMethod.Post, HttpMethod.Patch)) writes[path] = writes.getOrElse(path) { 0 } + 1
            val response = execute(request)
            if (path == lose) { lose = null; throw kotlinx.io.IOException("Response lost after mutation") }
            response
        }
        val auth = AuthRepositoryImpl(client, "http://test")
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), auth, session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val form = CreateTicketTypeViewModel(client, requests, session, "http://test")
        val fresh = CreateTicketTypeViewModel(client, requests, session, "http://test")
        val repository = OrganizerTicketTypesRepository(client, requests, "http://test")
        suspend fun settled(vm: CreateTicketTypeViewModel = form) { vm.state.first { !it.saving && !it.listLoading && it.hiding.isEmpty() } }
        suspend fun approve() {
            organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
        }
        suspend fun type(id: Int, name: String) {
            form.open(id); form.update("name", name); form.update("price", "0"); form.update("quantity", "10")
            form.update("sales_start", "2026-01-01T10:00:00+05:00"); form.update("sales_end", "2027-12-01T10:00:00+05:00")
            form.save(); settled()
        }
        try {
            runCurrent(); approve()
            create.open(); create.update("title", "Draft types"); create.update("start_time", "2027-12-01T19:00:00+05:00")
            create.update("end_time", "2027-12-01T22:00:00+05:00"); create.update("venue_name", "Hall"); create.update("venue_address", "Address")
            create.save(); create.state.first { !it.saving }
            val event = requireNotNull(create.state.value.created)
            type(event.id, "Same name"); type(event.id, "Same name")
            assertEquals(2, form.list(event.id).size)
            fresh.load(event.id); settled(fresh)
            assertEquals(2, fresh.list(event.id).size)
            assertTrue(managePaths.all { it == "/events/${event.id}/ticket-types/manage" })
            val ticket = fresh.list(event.id).first()
            val public = EventsRepositoryImpl(client, "http://test")
            assertFails { public.getTicketTypes(event.id) } // Draft remains unavailable publicly.
            assertEquals(200,client.patch("http://test/events/${event.id}/publish") { bearerAuth(session.tokens.value!!.accessToken) }.status.value)
            assertEquals(2,repository.list(event.id).size)
            assertEquals(2,public.getTicketTypes(event.id).size)

            fresh.edit(ticket); fresh.update("price", "1500"); fresh.update("description", "")
            lose = "/ticket-types/${ticket.id}"
            fresh.save(); settled(fresh)
            assertTrue(fresh.state.value.unknown)
            fresh.update("price", "1700"); fresh.checkEdited(); settled(fresh)
            assertFalse(fresh.state.value.unknown)
            assertEquals("1700", fresh.state.value.values["price"])
            assertEquals("1500", fresh.state.value.original["price"])
            assertNull(repository.get(event.id, ticket.id).description)
            assertEquals(1, writes["/ticket-types/${ticket.id}"])
            fresh.close()

            lose = "/ticket_types/${ticket.id}/hide"
            fresh.setHidden(repository.get(event.id, ticket.id), true); settled(fresh)
            assertTrue(ticket.id in fresh.state.value.hideUnknown)
            fresh.checkHidden(ticket); settled(fresh)
            assertTrue(fresh.list(event.id).first { it.id == ticket.id }.isHidden)
            assertTrue(public.getTicketTypes(event.id).none { it.id == ticket.id })
            assertFalse(ticket.id in fresh.state.value.hideUnknown)
            form.load(event.id); settled()
            assertTrue(form.list(event.id).first { it.id == ticket.id }.isHidden)
            lose = "/ticket_types/${ticket.id}/unhide"
            form.setHidden(form.list(event.id).first { it.id == ticket.id }, false); settled()
            assertTrue(ticket.id in form.state.value.hideUnknown)
            form.checkHidden(ticket); settled()
            assertFalse(ticket.id in form.state.value.hideUnknown)
            assertFalse(repository.get(event.id, ticket.id).isHidden)
            assertTrue(public.getTicketTypes(event.id).any { it.id == ticket.id })

            lose = "/events/${event.id}/ticket-type"
            type(event.id, "Same name")
            assertTrue(form.state.value.unknown)
            form.load(event.id); settled()
            assertEquals(3, form.list(event.id).size)
            assertNull(form.state.value.editing)
            assertTrue(form.state.value.unknown)
            val posted = writes["/events/${event.id}/ticket-type"]
            form.save(); runCurrent(); assertEquals(posted, writes["/events/${event.id}/ticket-type"])
            form.close()

            session.rotateTokens(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            assertEquals(3, repository.list(event.id).size)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            fresh.load(103); settled(fresh); assertTrue(fresh.list(103).isEmpty()); assertTrue(fresh.list(event.id).isEmpty())
            assertEquals(404, assertFailsWith<OrdersApiException> { repository.get(102, ticket.id) }.statusCode)
            assertEquals(200, client.patch("http://test/events/${event.id}/cancel") { bearerAuth(session.tokens.value!!.accessToken) }.status.value)
            assertEquals(3, repository.list(event.id).size) // Cancelled remains readable.

            auth.register("other-owner@example.kz", "password123")
            session.save(auth.login("other-owner@example.kz", "password123")); runCurrent(); approve()
            assertTrue(fresh.list(103).isEmpty()); assertNull(fresh.state.value.eventId)
            assertEquals(403, assertFailsWith<OrdersApiException> { repository.list(event.id) }.statusCode)
            session.clear(); runCurrent(); assertTrue(form.state.value.loaded.isEmpty())
        } finally {
            listOf(organizer, create, form, fresh).forEach { it.viewModelScope.cancel() }
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun lateListsCannotRestorePreviousEventOrAccountAndErrorsRemainVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val accountGate = CompletableDeferred<Unit>()
        var fail = true
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/101/")) withContext(NonCancellable) { gate.await() }
            if (request.url.encodedPath.contains("/103/")) withContext(NonCancellable) { accountGate.await() }
            if (fail && request.url.encodedPath.contains("/102/")) respond("""{"detail":"Route unavailable"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType,"application/json"))
            else respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"), AuthRepositoryImpl(client,"http://test"), session, backgroundScope)
        val vm = CreateTicketTypeViewModel(client, requests, session,"http://test")
        try {
            runCurrent(); vm.load(101); runCurrent(); vm.load(102); vm.state.first { !it.listLoading }
            assertTrue(vm.state.value.listError); assertTrue(vm.state.value.listErrorMessage!!.contains("404"))
            gate.complete(Unit); runCurrent(); assertEquals(102, vm.state.value.eventId); assertTrue(vm.state.value.listError)
            fail = false; vm.load(102); vm.state.first { !it.listLoading }; assertFalse(vm.state.value.listError)
            vm.load(103); runCurrent(); session.save(AuthTokens("new-account", "new-refresh", "bearer")); runCurrent()
            accountGate.complete(Unit); runCurrent(); assertNull(vm.state.value.eventId); assertTrue(vm.state.value.loaded.isEmpty())
        } finally { gate.complete(Unit); accountGate.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
