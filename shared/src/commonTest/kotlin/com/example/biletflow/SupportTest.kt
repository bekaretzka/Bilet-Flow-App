package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.auth.AuthViewModel
import com.example.biletflow.presentation.support.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SupportTest {
    @Test fun eventAndOrderContextOwnershipIdempotencyAndRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val bodies = mutableListOf<String>()
        client.plugin(HttpSend).intercept { request ->
            if(request.url.build().encodedPath == "/support/requests") bodies.add((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth = AuthRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo = SupportRepository(client,requests,"http://test")
        fun payload(key: Int) = SupportCreate(101,category="event_question",subject="Question",message="Message",
            clientRequestId="00000000-0000-4000-8000-${key.toString().padStart(12,'0')}")
        try {
            val event = repo.create(payload(1))
            assertEquals("open",event.status); assertNull(event.orderId); assertNull(event.ticketId)
            assertEquals(event,repo.create(payload(1)))
            val concurrent = (1..8).map { async { repo.create(payload(2)) } }.awaitAll()
            assertEquals(1,concurrent.map { it.id }.distinct().size)
            val order = repo.create(payload(3).copy(orderId=7102))
            assertEquals(7102,order.orderId)
            val ticket = repo.create(payload(4).copy(ticketId=71020))
            assertEquals(7102,ticket.orderId); assertEquals(71020,ticket.ticketId)
            assertEquals(409,assertFailsWith<SupportApiException> { repo.create(payload(1).copy(message="Different")) }.statusCode)
            assertEquals(422,assertFailsWith<SupportApiException> { repo.create(payload(5).copy(subject="x")) }.statusCode)
            assertEquals(404,assertFailsWith<SupportApiException> { repo.create(payload(6).copy(eventId=102,orderId=7102)) }.statusCode)
            assertEquals(404,assertFailsWith<SupportApiException> { repo.create(payload(7).copy(orderId=7103,ticketId=71020)) }.statusCode)
            session.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            val before = bodies.size
            repo.create(payload(8))
            assertEquals(2,bodies.size-before); assertEquals(bodies[before],bodies[before+1])
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            session.save(auth.login("empty@biletflow.kz","password123"))
            assertEquals(404,assertFailsWith<SupportApiException> { repo.create(payload(9).copy(orderId=7102)) }.statusCode)
            assertEquals(404,assertFailsWith<SupportApiException> { repo.create(payload(10).copy(ticketId=71020)) }.statusCode)
            assertNotEquals(event.id,repo.create(payload(1)).id) // Same key belongs to another user.
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test fun lostResponseManualRetryKeepsBodyDoubleClickAndEditingUsesNewId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val bodies = mutableListOf<JsonObject>()
        var hold: CompletableDeferred<Unit>? = null
        client.plugin(HttpSend).intercept { request ->
            if(request.url.build().encodedPath == "/support/requests") {
                bodies.add(Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject)
                hold?.await()
            }
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val vm = SupportViewModel(client,requests,session,"http://test")
        try {
            runCurrent(); vm.open(101); vm.submit(); assertEquals(setOf("subject","message"),vm.state.value.errors); assertTrue(bodies.isEmpty())
            vm.update("subject","  support-unknown  "); vm.update("message","  Keep this message  ")
            hold = CompletableDeferred(); vm.submit(); vm.submit(); runCurrent()
            assertTrue(vm.state.value.sending); assertEquals(1,bodies.size)
            hold!!.complete(Unit); hold = null; vm.state.first { !it.sending }
            assertTrue(vm.state.value.unknown); assertNull(vm.state.value.created)
            assertEquals("  Keep this message  ",vm.state.value.message)
            vm.submit(); vm.state.first { !it.sending }
            val first = requireNotNull(vm.state.value.created)
            assertEquals(9000,first.id); assertEquals(bodies[0],bodies[1])
            vm.submit(); runCurrent(); assertEquals(2,bodies.size)

            vm.open(101,7102); vm.update("subject","support-unknown"); vm.update("message","Another issue")
            vm.submit(); vm.state.first { !it.sending }; assertTrue(vm.state.value.unknown)
            val oldKey = bodies.last().getValue("client_request_id")
            vm.update("subject","Edited request"); assertTrue(vm.state.value.previousUnknown)
            vm.submit(); vm.state.first { !it.sending }
            assertNotEquals(oldKey,bodies.last().getValue("client_request_id"))
            assertEquals(9002,vm.state.value.created!!.id) // Only one record for the first manual retry.
            assertEquals(7102,vm.state.value.created!!.orderId)
            assertEquals(2,supportTextLength("😀😀"))
        } finally { hold?.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun guestLoginPreservesFormAndAccountChangeIgnoresLateResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val gate = CompletableDeferred<Unit>()
        var hold = false
        client.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if(hold && request.url.build().encodedPath == "/support/requests") withContext(NonCancellable) { gate.await() }
            response
        }
        val session = SessionStore()
        val auth = AuthRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val vm = SupportViewModel(client,requests,session,"http://test")
        val login = AuthViewModel(auth,session)
        try {
            runCurrent(); vm.open(101); vm.update("subject","Guest question"); vm.update("message","Keep this form")
            vm.submit(); assertTrue(vm.state.value.waitingForLogin)
            login.updateEmail("visitor@biletflow.kz"); login.updatePassword("password123"); login.login()
            login.state.first { it.isSuccess }; runCurrent()
            assertEquals(101,vm.state.value.eventId); assertEquals("Keep this form",vm.state.value.message)
            assertFalse(vm.state.value.waitingForLogin)
            hold = true; vm.submit(); runCurrent(); assertTrue(vm.state.value.sending)
            session.save(auth.login("empty@biletflow.kz","password123")); runCurrent()
            assertNull(vm.state.value.eventId); assertEquals("",vm.state.value.message)
            gate.complete(Unit); runCurrent(); assertNull(vm.state.value.created)
            vm.open(102); vm.close(); assertNull(vm.state.value.eventId)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); login.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
