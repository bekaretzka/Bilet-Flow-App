package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.auth.AuthViewModel
import com.example.biletflow.presentation.organizer.OrganizerViewModel
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
    @Test fun suspensionFromCasesEndsCurrentSession()=runTest {
        val client=createMockEventsHttpClient()
        try {
            val auth=AuthRepositoryImpl(client,"http://test")
            auth.register("suspend-on-cases@biletflow.kz","password123")
            val store=SessionStore().apply {save(auth.login("suspend-on-cases@biletflow.kz","password123"))}
            val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
            assertFailsWith<OrdersApiException> {SupportCasesRepository(client,requests,"http://test").mine()}
            assertNull(store.tokens.value);assertTrue(store.suspended.value)
        } finally {client.close()}
    }
    private fun session()=SessionStore().apply {save(AuthTokens("mock-access-token","mock-refresh-token","bearer"))}
    @Test fun currentCaseContractSingleContextCategoriesOwnershipAndRefresh()=runTest {
        val client=createMockEventsHttpClient()
        val bodies=mutableListOf<JsonObject>()
        client.plugin(HttpSend).intercept {request ->
            if(request.url.build().encodedPath=="/support/cases") bodies.add(Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject)
            execute(request)
        }
        val store=session();val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val repo=SupportCasesRepository(client,requests,"http://test")
        val payload=SupportCaseCreate(category="event_information",subject="Q",message="Message",eventId=101)
        try {
            val event=repo.create(payload);assertEquals("open",event.status);assertEquals(1,event.messages.size)
            val order=repo.create(payload.copy(eventId=null,orderId=7102));assertEquals(101,order.eventId);assertEquals(7102,order.orderId)
            val ticket=repo.create(payload.copy(eventId=null,ticketId=71020));assertEquals(7102,ticket.orderId);assertEquals(71020,ticket.ticketId)
            assertTrue(bodies.all {it["kind"]?.jsonPrimitive?.content=="attendee" && listOf("event_id","order_id","ticket_id").count(it::containsKey)==1 && "client_request_id" !in it})
            assertEquals(422,assertFailsWith<SupportApiException> {repo.create(payload.copy(category="event_question"))}.statusCode)
            assertEquals(422,assertFailsWith<SupportApiException> {repo.create(payload.copy(orderId=7102))}.statusCode)
            assertEquals(422,assertFailsWith<SupportApiException> {repo.create(payload.copy(subject=" "))}.statusCode)
            assertEquals(422,assertFailsWith<SupportApiException> {repo.create(payload.copy(message="x".repeat(5001)))}.statusCode)
            store.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            val before=bodies.size;repo.create(payload);assertEquals(2,bodies.size-before)
            assertTrue(store.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            store.save(auth.login("empty@biletflow.kz","password123"))
            assertEquals(404,assertFailsWith<SupportApiException> {repo.create(payload.copy(eventId=null,orderId=7102))}.statusCode)
            assertEquals(404,assertFailsWith<SupportApiException> {repo.create(payload.copy(eventId=null,ticketId=71020))}.statusCode)
            assertEquals(404,assertFailsWith<SupportApiException> {repo.get(event.id)}.statusCode)
            assertTrue(repo.mine().isEmpty())
        } finally {client.close()}
    }
    @Test fun unknownCreationPreservesTextBlocksDoubleClickAndOffersListWithoutAnotherPost()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient();val gate=CompletableDeferred<Unit>();var posts=0
        client.plugin(HttpSend).intercept {request ->
            if(request.url.build().encodedPath=="/support/cases") {posts++;gate.await()}
            execute(request)
        }
        val store=session();val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),store,backgroundScope)
        val vm=SupportViewModel(client,requests,store,"http://test")
        try {
            runCurrent();vm.open(101);vm.submit();assertEquals(setOf("subject","message"),vm.state.value.errors)
            vm.update("subject","support-case-unknown");vm.update("message"," Keep my text ")
            vm.submit();vm.submit();runCurrent();assertEquals(1,posts)
            gate.complete(Unit);vm.state.first {!it.sending};assertTrue(vm.state.value.unknown)
            assertEquals(" Keep my text ",vm.state.value.message)
            vm.submit();runCurrent();assertEquals(1,posts)
            val mine=SupportCasesRepository(client,requests,"http://test").mine()
            assertEquals(1,mine.size);assertEquals("support-case-unknown",mine.single().subject)
            vm.update("message","Edited text");vm.submit();runCurrent();assertEquals(1,posts);assertTrue(vm.state.value.previousUnknown)
            assertEquals(2,supportTextLength("😀😀"))
        } finally {gate.complete(Unit);vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
    @Test fun guestLoginPreservesFormAndAccountChangeIgnoresLateResponse()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient();val gate=CompletableDeferred<Unit>();var hold=false
        client.plugin(HttpSend).intercept {request ->
            val response=execute(request)
            if(hold && request.url.build().encodedPath=="/support/cases") withContext(NonCancellable) {gate.await()}
            response
        }
        val store=SessionStore();val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val vm=SupportViewModel(client,requests,store,"http://test");val login=AuthViewModel(auth,store)
        try {
            runCurrent();vm.open(101,7102);vm.update("subject","Guest question");vm.update("message","Keep this form");vm.submit()
            login.updateEmail("visitor@biletflow.kz");login.updatePassword("password123");login.login();login.state.first {it.isSuccess};runCurrent()
            assertEquals(7102,vm.state.value.orderId);assertEquals("Keep this form",vm.state.value.message)
            hold=true;vm.submit();runCurrent();assertTrue(vm.state.value.sending)
            store.save(auth.login("empty@biletflow.kz","password123"));runCurrent();assertNull(vm.state.value.eventId)
            gate.complete(Unit);runCurrent();assertNull(vm.state.value.created)
        } finally {gate.complete(Unit);vm.viewModelScope.cancel();login.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
    @Test fun arrayListsInboxFiltersExactCasePreviewAndRequesterIsolation()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient();val store=session();val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val repo=SupportCasesRepository(client,requests,"http://test")
        val organizer=OrganizerViewModel(client,requests,store,"http://test");val vm=SupportCasesViewModel(client,requests,store,"http://test")
        try {
            runCurrent();organizer.openForm();organizer.apply();organizer.state.first {!it.loading}
            repeat(3) {organizer.load();organizer.state.first {!it.loading}}
            store.save(auth.login("empty@biletflow.kz","password123"));runCurrent()
            val first=repo.create(SupportCaseCreate(category="payment",subject="Event 101",message="First",eventId=101))
            val second=repo.create(SupportCaseCreate(category="technical",subject="Event 102",message="Second",eventId=102))
            assertEquals(2,repo.mine().size);assertTrue(repo.mine().all {it.messages.isEmpty()})
            assertEquals(403,assertFailsWith<SupportApiException> {repo.inbox()}.statusCode)
            store.save(auth.login("visitor@biletflow.kz","password123"));runCurrent()
            assertTrue(repo.mine().isEmpty());assertEquals(setOf(first.id,second.id),repo.inbox().map {it.id}.toSet())
            vm.openEvent(101,"Event");vm.state.first {!it.loading};assertEquals(listOf(first.id),vm.state.value.items.map {it.id})
            vm.select(first.id);vm.state.first {!it.loading};assertEquals("First",vm.state.value.detail?.messages?.single()?.body)
            vm.back();vm.state.first {!it.loading};assertEquals(101,vm.state.value.eventId)
            store.clear();runCurrent();assertFalse(vm.state.value.open);assertTrue(vm.state.value.items.isEmpty())
        } finally {vm.viewModelScope.cancel();organizer.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
    @Test fun listRetryAndLateListAfterLogoutCannotRestoreData()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient();val auth=AuthRepositoryImpl(client,"http://test")
        auth.register("cases-offline@biletflow.kz","password123")
        val store=SessionStore().apply {save(auth.login("cases-offline@biletflow.kz","password123"))}
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val vm=SupportCasesViewModel(client,requests,store,"http://test")
        val gate=CompletableDeferred<Unit>();var hold=false
        client.plugin(HttpSend).intercept {request ->val response=execute(request);if(hold) withContext(NonCancellable) {gate.await()};response}
        try {
            runCurrent();vm.open();vm.state.first {!it.loading};assertNotNull(vm.state.value.error);assertNotNull(store.tokens.value)
            vm.reload();vm.state.first {!it.loading};assertNull(vm.state.value.error)
            hold=true;vm.reload();runCurrent();store.clear();runCurrent();gate.complete(Unit);runCurrent()
            assertFalse(vm.state.value.open);assertTrue(vm.state.value.items.isEmpty())
        } finally {gate.complete(Unit);vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
}
