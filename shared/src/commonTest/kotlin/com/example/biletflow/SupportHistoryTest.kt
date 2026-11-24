package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.support.SupportHistoryViewModel
import io.ktor.client.HttpClient
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
class SupportHistoryTest {
    @Test fun createdRequestsPaginationFirstMessageOwnershipAndRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth = AuthRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo = SupportRepository(client,requests,"http://test")
        try {
            assertTrue(repo.list().items.isEmpty())
            val made = (1..3).map { n -> repo.create(SupportCreate(101,category="other",subject="Question $n",message="First $n",
                clientRequestId="00000000-0000-4000-8000-${n.toString().padStart(12,'0')}")) }
            repo.create(SupportCreate(101,category="other",subject="Question 1",message="First 1",clientRequestId="00000000-0000-4000-8000-000000000001"))
            var cursor: String? = null
            val ids = mutableListOf<Int>()
            do { val page = repo.list(cursor,1); ids.addAll(page.items.map { it.id }); cursor = page.nextCursor } while(cursor != null)
            assertEquals(made.reversed().map { it.id },ids)
            val one = repo.get(made.first().id)
            assertEquals(one.createdAt,one.updatedAt); assertNotNull(one.eventTitle)
            val messages = repo.messages(one.id,limit=1)
            assertEquals(1,messages.items.size); assertEquals("First 1",messages.items.single().text); assertNull(messages.nextCursor)
            session.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            assertEquals(3,repo.list().items.size)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access"))
            session.save(auth.login("empty@biletflow.kz","password123"))
            assertTrue(repo.list().items.isEmpty())
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.get(one.id) }.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.messages(one.id) }.statusCode)
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test fun messagesPagingRetryDeduplicationBackAndLateSessionResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        var hold = false
        val gate = CompletableDeferred<Unit>()
        val record = """{"id":9,"event_id":101,"order_id":null,"ticket_id":null,"status":"open","category":"other","subject":"Question","message":"First","client_request_id":"00000000-0000-4000-8000-000000000009","created_at":"2026-11-24T14:00:00+05:00"}"""
        val client = HttpClient(MockEngine { request ->
            if(hold) withContext(NonCancellable) { gate.await() }
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/me") && fail -> { fail = false; return@MockEngine respond("""{"detail":"Temporary failure"}""",HttpStatusCode.ServiceUnavailable,headersOf(HttpHeaders.ContentType,"application/json")) }
                path.endsWith("/me") -> """{"items":[$record],"next_cursor":null}"""
                path.endsWith("/messages") -> {
                    val older = request.url.parameters["cursor"] != null
                    val range = if(older) (2 downTo 1) else (51 downTo 2)
                    buildJsonObject {
                        put("items",JsonArray(range.map { id -> buildJsonObject {
                            put("id",id); put("request_id",9); put("author_name",JsonNull); put("author_role",if(id%2==0) "organizer" else "visitor")
                            put("text","Message $id"); put("created_at","2026-11-24T14:00:00+05:00")
                        } }))
                        put("next_cursor",if(older) JsonNull else JsonPrimitive("older"))
                    }.toString()
                }
                else -> record
            }
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        val session = SessionStore()
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val vm = SupportHistoryViewModel(client,requests,session,"http://test")
        try {
            runCurrent(); vm.open(); assertTrue(vm.state.value.open); assertFalse(vm.state.value.loading)
            session.save(AuthTokens("a","r","bearer")); vm.state.first { it.error != null }
            assertNotNull(vm.state.value.error); vm.retryList(); vm.state.first { !it.loading }
            assertEquals(9,vm.state.value.items.single().id)
            vm.select(9); vm.state.first { !it.detailLoading && !it.messagesLoading }; assertEquals(50,vm.state.value.messages.size)
            vm.older(); vm.older(); vm.state.first { !it.messagesLoading }
            assertEquals((51 downTo 1).toList(),vm.state.value.messages.map { it.id }); assertNull(vm.state.value.olderCursor)
            vm.refreshThread(); vm.state.first { !it.detailLoading && !it.messagesLoading }; assertEquals(51,vm.state.value.messages.size)
            vm.back(); assertNull(vm.state.value.selectedId); assertEquals(1,vm.state.value.items.size)
            vm.select(9,fromCreation=true); vm.state.first { !it.detailLoading && !it.messagesLoading }; vm.back(); assertFalse(vm.state.value.open)
            vm.open(); vm.state.first { !it.loading }; hold = true; vm.select(9); runCurrent()
            session.clear(); runCurrent(); assertFalse(vm.state.value.open)
            gate.complete(Unit); runCurrent(); assertNull(vm.state.value.request); assertTrue(vm.state.value.messages.isEmpty())
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
