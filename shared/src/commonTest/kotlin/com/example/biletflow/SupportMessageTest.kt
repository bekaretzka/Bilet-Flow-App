package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.support.SupportHistoryViewModel
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SupportMessageTest {
    private fun payload(n: Int, body: String) = SupportMessageCreate(body,"00000000-0000-4000-8000-${n.toString().padStart(12,'0')}")
    private suspend fun create(repo: SupportRepository, subject: String = "Question") = repo.create(SupportCreate(101,
        category="other",subject=subject,message="First",clientRequestId="00000000-0000-4000-8000-999999999999"))

    @Test fun contractIdempotencyOwnershipClosedValidationAndRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth = AuthRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo = SupportRepository(client,requests,"http://test")
        try {
            val request = create(repo)
            val message = repo.send(request.id,payload(1,"Hello"))
            assertEquals("visitor",message.authorRole); assertEquals("Hello",message.text)
            assertEquals(message,repo.send(request.id,payload(1,"Hello")))
            assertEquals(409,assertFailsWith<OrdersApiException> { repo.send(request.id,payload(1,"Changed")) }.statusCode)
            assertEquals(422,assertFailsWith<OrdersApiException> { repo.send(request.id,payload(2," ")) }.statusCode)
            assertEquals(422,assertFailsWith<OrdersApiException> { repo.send(request.id,payload(2,"x".repeat(5001))) }.statusCode)
            assertFails { repo.send(request.id,payload(3,"message-unknown")) }
            val recovered = repo.send(request.id,payload(3,"message-unknown"))
            assertEquals(3,repo.messages(request.id).items.size)
            assertEquals(1,repo.messages(request.id).items.count { it.id == recovered.id })
            session.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            repo.send(request.id,payload(4,"After refresh"))
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access"))
            assertEquals(4,repo.messages(request.id).items.size)
            val closed = repo.create(SupportCreate(101,category="other",subject="support-closed",message="First",clientRequestId=payload(5,"").clientRequestId))
            assertEquals("resolved",repo.get(closed.id).status)
            assertEquals("SupportRequestClosedError",assertFailsWith<OrdersApiException> { repo.send(closed.id,payload(6,"No")) }.errorCode)
            session.save(auth.login("empty@biletflow.kz","password123"))
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.send(request.id,payload(7,"No")) }.statusCode)
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test fun sendDoubleClickLostResponseDraftAndSessionGuards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val sent = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        client.plugin(HttpSend).intercept { request ->
            if(request.method == HttpMethod.Post && request.url.build().encodedPath.endsWith("/messages")) {
                sent.add((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                hold?.await()
            }
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val repo = SupportRepository(client,requests,"http://test")
        val vm = SupportHistoryViewModel(client,requests,session,"http://test")
        try {
            val request = create(repo); runCurrent(); vm.select(request.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            vm.send(); assertTrue(vm.state.value.invalidDraft); assertTrue(sent.isEmpty())
            vm.updateDraft("x".repeat(5001)); vm.send(); assertTrue(vm.state.value.invalidDraft)
            vm.updateDraft("message-unknown"); hold = CompletableDeferred(); vm.send(); vm.send(); runCurrent()
            assertEquals(1,sent.size); assertTrue(vm.state.value.sending)
            hold!!.complete(Unit); hold=null; vm.state.first { !it.sending }
            assertTrue(vm.state.value.unknownSend); assertEquals("message-unknown",vm.state.value.draft)
            vm.send(); vm.state.first { !it.sending }
            assertEquals(sent[0],sent[1]); assertEquals("",vm.state.value.draft)
            assertEquals(2,repo.messages(request.id).items.size)
            vm.updateDraft("message-unknown"); vm.send(); vm.state.first { !it.sending }
            val previous = sent.last(); vm.updateDraft("Edited"); vm.send(); vm.state.first { !it.sending }
            assertNotEquals(Json.parseToJsonElement(previous).jsonObject["client_request_id"],Json.parseToJsonElement(sent.last()).jsonObject["client_request_id"])
            vm.updateDraft("Late"); hold = CompletableDeferred(); vm.send(); runCurrent()
            vm.select(request.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            hold!!.complete(Unit); hold=null; runCurrent()
            assertEquals("",vm.state.value.draft); assertNull(vm.state.value.sendError)
            session.clear(); runCurrent(); assertTrue(vm.state.value.messages.isEmpty()); assertFalse(vm.state.value.open)
        } finally { hold?.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun pollingAllNewPagesAndHistoryDeduplicationStopsInBackground() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        var reads = 0
        client.plugin(HttpSend).intercept { request ->
            if(request.method == HttpMethod.Get && request.url.build().encodedPath.endsWith("/messages")) reads++
            execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val repo = SupportRepository(client,requests,"http://test")
        val vm = SupportHistoryViewModel(client,requests,session,"http://test")
        try {
            val request = create(repo); runCurrent(); vm.select(request.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            repeat(55) { repo.send(request.id,payload(it+1,"Message $it")) }
            vm.visibleLatest(false); vm.refreshNew(); vm.state.first { !it.newLoading }
            assertEquals(56,vm.state.value.messages.size); assertEquals(55,vm.state.value.unread)
            assertNull(vm.state.value.olderCursor)
            vm.select(request.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            assertEquals(50,vm.state.value.messages.size); assertNotNull(vm.state.value.olderCursor)
            repo.send(request.id,payload(56,"During history"))
            vm.older(); vm.refreshNew(); vm.state.first { !it.newLoading && !it.messagesLoading }
            assertEquals(57,vm.state.value.messages.size); assertEquals(57,vm.state.value.messages.map { it.id }.distinct().size)
            vm.setForeground(true); val before = reads
            advanceTimeBy(10_001); runCurrent(); vm.state.first { !it.newLoading }
            assertTrue(reads > before)
            vm.setForeground(false); val stopped = reads; advanceTimeBy(30_000); runCurrent(); assertEquals(stopped,reads)
            vm.setForeground(true); vm.back(); val backed = reads; advanceTimeBy(30_000); runCurrent(); assertEquals(backed,reads)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
