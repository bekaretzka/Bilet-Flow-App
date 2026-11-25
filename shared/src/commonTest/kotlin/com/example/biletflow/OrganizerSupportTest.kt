package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.OrganizerViewModel
import com.example.biletflow.presentation.support.SupportHistoryViewModel
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizerSupportTest {
    @Test fun ownerRepliesVisitorReadsPaginationRefreshAndStaffHasNoAccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth = AuthRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo = SupportRepository(client,requests,"http://test")
        val organizer = OrganizerViewModel(client,requests,session,"http://test")
        suspend fun approve() {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertEquals("approved",organizer.state.value.profile?.status)
        }
        fun key(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
        try {
            approve()
            val owner = requireNotNull(session.tokens.value)
            val visitor = auth.login("empty@biletflow.kz","password123")
            session.save(visitor); runCurrent()
            val made = (1..3).map { repo.create(SupportCreate(101,category="other",subject="Question $it",message="Visitor question",clientRequestId=key(it))) }
            val otherEvent = repo.create(SupportCreate(102,category="other",subject="Other event",message="Other question",clientRequestId=key(4)))
            assertEquals(403,assertFailsWith<OrdersApiException> { repo.eventList(101) }.statusCode)
            session.save(owner); runCurrent()
            var cursor: String? = null
            val ids = mutableListOf<Int>()
            do { val page=repo.eventList(101,cursor,1); ids.addAll(page.items.map { it.id }); cursor=page.nextCursor } while(cursor != null)
            assertEquals(made.reversed().map { it.id },ids)
            assertEquals("empty@biletflow.kz",repo.eventList(101).items.first().authorEmail)
            assertTrue(repo.list().items.isEmpty()) // Owner list is distinct from authored requests.
            assertEquals(1,repo.eventList(102).items.size)
            val request = made.first()
            val payload = SupportMessageCreate("Owner reply",key(20))
            val reply=repo.send(request.id,payload)
            assertEquals("organizer",reply.authorRole)
            assertEquals(reply,repo.send(request.id,payload))
            assertFails { repo.send(request.id,SupportMessageCreate("message-unknown",key(21))) }
            val recovered = repo.send(request.id,SupportMessageCreate("message-unknown",key(21)))
            assertEquals(3,repo.messages(request.id).items.size)
            assertEquals(1,repo.messages(request.id).items.count { it.id == recovered.id })
            assertEquals(409,assertFailsWith<OrdersApiException> { repo.send(request.id,payload.copy(body="Changed")) }.statusCode)
            session.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            assertEquals(3,repo.eventList(101).items.size)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access"))
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.eventList(999999) }.statusCode)
            // Assign a normal account as event staff; check-in access does not grant support access.
            auth.register("staff-support@biletflow.kz","password123")
            val staff = auth.login("staff-support@biletflow.kz","password123")
            val assigned = client.post("http://test/events/101/staff") {
                bearerAuth(session.tokens.value!!.accessToken); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("email","staff-support@biletflow.kz") })
            }
            assertEquals(201,assigned.status.value)
            session.save(staff); runCurrent()
            assertEquals(403,assertFailsWith<OrdersApiException> { repo.eventList(101) }.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.get(request.id) }.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.messages(request.id) }.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.send(request.id,SupportMessageCreate("No",key(22))) }.statusCode)
            approve() // Even another approved organizer cannot access the owner's requests.
            assertEquals(403,assertFailsWith<OrdersApiException> { repo.eventList(101) }.statusCode)
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.get(otherEvent.id) }.statusCode)
            auth.register("outsider-support@biletflow.kz","password123")
            session.save(auth.login("outsider-support@biletflow.kz","password123")); runCurrent()
            assertEquals(404,assertFailsWith<OrdersApiException> { repo.get(request.id) }.statusCode)
            session.save(visitor); runCurrent()
            val received=repo.messages(request.id,afterId=request.id)
            assertEquals(listOf("Owner reply","message-unknown"),received.items.map { it.text })
            assertTrue(received.items.all { it.authorRole == "organizer" })
            assertEquals(request.id,repo.list().items.first().id) // Reply updates ordering.
        } finally { organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun organizerListRetryThreadReturnAndLateSessionReplies() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val session=SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo=SupportRepository(client,requests,"http://test")
        val organizer=OrganizerViewModel(client,requests,session,"http://test")
        val vm=SupportHistoryViewModel(client,requests,session,"http://test")
        var fail=true
        var hold: CompletableDeferred<Unit>?=null
        var posts=0
        client.plugin(HttpSend).intercept { request ->
            val path=request.url.build().encodedPath
            if(path == "/organizer/events/101/support/requests" && fail) { fail=false; throw kotlinx.io.IOException("Mock list offline") }
            if(path.endsWith("/messages") && request.method == HttpMethod.Post) { posts++; hold?.await() }
            execute(request)
        }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            val owner=session.tokens.value!!
            session.save(auth.login("empty@biletflow.kz","password123")); runCurrent()
            val made=repo.create(SupportCreate(101,category="other",subject="Visitor question",message="Hello",clientRequestId="00000000-0000-4000-8000-000000000001"))
            session.save(owner); runCurrent(); vm.openEvent(101,"Owned event"); vm.state.first { !it.loading }
            assertNotNull(vm.state.value.error); vm.retryList(); vm.state.first { !it.loading }
            assertEquals(made.id,vm.state.value.items.single().id)
            vm.select(made.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            vm.updateDraft("Owner reply"); hold=CompletableDeferred(); vm.send(); vm.send(); runCurrent()
            assertEquals(1,posts); hold!!.complete(Unit); hold=null; vm.state.first { !it.sending }
            assertEquals("organizer",vm.state.value.messages.first().authorRole)
            vm.back(); vm.state.first { !it.loading }
            assertNull(vm.state.value.selectedId); assertEquals(101,vm.state.value.eventId)
            assertEquals("2026-11-25T10:00:00+05:00",vm.state.value.items.single().updatedAt)
            vm.select(made.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            vm.setForeground(true); vm.updateDraft("Late"); hold=CompletableDeferred(); vm.send(); runCurrent()
            vm.openEvent(102,"Other"); assertTrue(vm.state.value.messages.isEmpty()); assertEquals("",vm.state.value.draft)
            hold!!.complete(Unit); hold=null; runCurrent(); assertTrue(vm.state.value.messages.isEmpty())
            session.clear(); runCurrent(); assertFalse(vm.state.value.open); assertTrue(vm.state.value.items.isEmpty())
        } finally { hold?.complete(Unit); vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
