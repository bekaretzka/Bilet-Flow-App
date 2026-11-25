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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SupportStatusTest {
    @Test fun historyFixtureHasTwoPagesWithoutDuplicatingFirstMessage() = runTest {
        val client=createMockEventsHttpClient()
        val session=SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),session,backgroundScope)
        val repo=SupportRepository(client,requests,"http://test")
        try {
            val request=repo.create(SupportCreate(101,category="other",subject="support-history",message="First",clientRequestId="00000000-0000-4000-8000-000000000050"))
            val first=repo.messages(request.id)
            assertEquals(50,first.items.size); assertNotNull(first.nextCursor)
            val older=repo.messages(request.id,first.nextCursor)
            val all=first.items+older.items
            assertEquals(66,all.size); assertEquals(66,all.map { it.id }.distinct().size)
            assertEquals(1,all.count { it.text == "First" }); assertNull(older.nextCursor)
        } finally { client.close() }
    }
    @Test fun transitionsReopenMessagesVisitorPollingRefreshAndRights() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val ownerSession=SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth=AuthRepositoryImpl(client,"http://test")
        val ownerRequests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,ownerSession,backgroundScope)
        val ownerRepo=SupportRepository(client,ownerRequests,"http://test")
        val organizer=OrganizerViewModel(client,ownerRequests,ownerSession,"http://test")
        val visitorSession=SessionStore()
        val visitorRequests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,visitorSession,backgroundScope)
        val visitorRepo=SupportRepository(client,visitorRequests,"http://test")
        val visitorVm=SupportHistoryViewModel(client,visitorRequests,visitorSession,"http://test")
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            visitorSession.save(auth.login("empty@biletflow.kz","password123")); runCurrent()
            val request=visitorRepo.create(SupportCreate(101,category="other",subject="Statuses",message="First",clientRequestId="00000000-0000-4000-8000-000000000001"))
            assertFalse(visitorRepo.get(request.id).canManageStatus); assertTrue(ownerRepo.get(request.id).canManageStatus)
            assertEquals("in_progress",canonicalSupportStatus("pending")); assertEquals("resolved",canonicalSupportStatus("closed"))
            var current="open"
            for(target in listOf("in_progress","waiting_for_customer","resolved")) {
                val changed=ownerRepo.changeStatus(request.id,SupportStatusChange(target,current))
                assertEquals(target,changed.status); assertEquals(target,visitorRepo.get(request.id).status)
                assertEquals(target,ownerRepo.eventList(101).items.single().status); current=target
            }
            assertEquals("SupportStatusTransitionError",assertFailsWith<OrdersApiException> {
                ownerRepo.changeStatus(request.id,SupportStatusChange("in_progress","resolved"))
            }.errorCode)
            val message=SupportMessageCreate("New message","00000000-0000-4000-8000-000000000002")
            assertEquals("SupportRequestClosedError",assertFailsWith<OrdersApiException> { visitorRepo.send(request.id,message) }.errorCode)
            assertEquals("SupportRequestClosedError",assertFailsWith<OrdersApiException> { ownerRepo.send(request.id,message) }.errorCode)
            ownerRepo.changeStatus(request.id,SupportStatusChange("open","resolved"))
            assertEquals("New message",visitorRepo.send(request.id,message).text)
            assertEquals("SupportStatusConflictError",assertFailsWith<OrdersApiException> {
                ownerRepo.changeStatus(request.id,SupportStatusChange("resolved","waiting_for_customer"))
            }.errorCode)
            assertEquals(422,assertFailsWith<OrdersApiException> {
                ownerRepo.changeStatus(request.id,SupportStatusChange("closed","open"))
            }.statusCode)
            visitorVm.select(request.id); visitorVm.state.first { !it.detailLoading && !it.messagesLoading }
            visitorVm.setForeground(true)
            ownerRepo.changeStatus(request.id,SupportStatusChange("resolved","open"))
            advanceTimeBy(10_001); runCurrent(); visitorVm.state.first { !it.detailLoading && !it.newLoading }
            assertEquals("resolved",visitorVm.state.value.request?.status)
            visitorVm.updateDraft("Still blocked"); visitorVm.send(); assertFalse(visitorVm.state.value.sending)
            ownerSession.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            ownerRepo.changeStatus(request.id,SupportStatusChange("open","resolved"))
            assertTrue(ownerSession.tokens.value!!.accessToken.startsWith("mock-rotated-access"))
            advanceTimeBy(10_001); runCurrent(); visitorVm.state.first { !it.detailLoading && !it.newLoading }
            assertEquals("open",visitorVm.state.value.request?.status)
            assertEquals(403,assertFailsWith<OrdersApiException> {
                visitorRepo.changeStatus(request.id,SupportStatusChange("resolved","open"))
            }.statusCode)
            // The same user remains unable to change status after receiving check-in staff access.
            val assigned=client.post("http://test/events/101/staff") {
                bearerAuth(ownerSession.tokens.value!!.accessToken); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("email","empty@biletflow.kz") })
            }
            assertEquals(201,assigned.status.value)
            assertEquals(403,assertFailsWith<OrdersApiException> {
                visitorRepo.changeStatus(request.id,SupportStatusChange("resolved","open"))
            }.statusCode)
        } finally { visitorVm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun lostStatusResponseSinglePatchCheckAndLateAccountResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val session=SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo=SupportRepository(client,requests,"http://test")
        val organizer=OrganizerViewModel(client,requests,session,"http://test")
        val vm=SupportHistoryViewModel(client,requests,session,"http://test")
        var patches=0
        var hold: CompletableDeferred<Unit>?=null
        client.plugin(HttpSend).intercept { request ->
            if(request.method == HttpMethod.Patch && request.url.build().encodedPath.endsWith("/status")) {
                patches++; hold?.await()
            }
            execute(request)
        }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            val request=repo.create(SupportCreate(102,category="other",subject="status-unknown",message="First",clientRequestId="00000000-0000-4000-8000-000000000003"))
            vm.openEvent(102,"Event"); vm.state.first { !it.loading }
            vm.select(request.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            hold=CompletableDeferred(); vm.changeStatus("in_progress"); vm.changeStatus("resolved"); runCurrent()
            assertEquals(1,patches); assertTrue(vm.state.value.statusBusy)
            hold!!.complete(Unit); hold=null; vm.state.first { !it.statusBusy }
            assertTrue(vm.state.value.statusUnknown)
            vm.changeStatus("resolved"); runCurrent(); assertEquals(1,patches)
            assertEquals("in_progress",repo.get(request.id).status)
            vm.readDetails(); vm.state.first { !it.detailLoading }
            assertFalse(vm.state.value.statusUnknown); assertEquals("in_progress",vm.state.value.request?.status)
            assertEquals(1,patches) // Only GET reconciled the lost result.
            vm.changeStatus("waiting_for_customer"); vm.state.first { !it.statusBusy }
            assertEquals("waiting_for_customer",vm.state.value.request?.status)
            vm.back(); vm.state.first { !it.loading }; assertEquals("waiting_for_customer",vm.state.value.items.single().status)
            vm.select(request.id); vm.state.first { !it.detailLoading && !it.messagesLoading }
            hold=CompletableDeferred(); vm.changeStatus("resolved"); runCurrent()
            session.clear(); runCurrent(); assertFalse(vm.state.value.open)
            hold!!.complete(Unit); hold=null; runCurrent()
            assertNull(vm.state.value.request); assertFalse(vm.state.value.statusBusy)
        } finally { hold?.complete(Unit); vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
