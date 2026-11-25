package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.OrganizerViewModel
import com.example.biletflow.presentation.support.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

/** Step 52F: messages and statuses on the real /support/cases contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportThreadTest {
    private class World(val store:SessionStore,val auth:AuthRepositoryImpl,val requests:SessionOrdersRepository,val repo:SupportCasesRepository,
        val organizer:AuthTokens,val attendee:AuthTokens)
    /** visitor@biletflow.kz (id 100) becomes the approved owner of seed events 101-103; empty@biletflow.kz (id 101) is the attendee. */
    private suspend fun TestScope.world(client:HttpClient):World {
        val store=SessionStore().apply {save(AuthTokens("mock-access-token","mock-refresh-token","bearer"))}
        val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val organizer=OrganizerViewModel(client,requests,store,"http://test")
        runCurrent();organizer.openForm();organizer.apply();organizer.state.first {!it.loading}
        repeat(3) {organizer.load();organizer.state.first {!it.loading}}
        organizer.viewModelScope.cancel()
        return World(store,auth,requests,SupportCasesRepository(client,requests,"http://test"),store.tokens.value!!,auth.login("empty@biletflow.kz","password123"))
    }
    private fun bodyOf(request:HttpRequestBuilder)=Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
    private fun isPost(request:HttpRequestBuilder)=request.method==HttpMethod.Post && request.url.build().encodedPath.endsWith("/messages")
    private fun isCaseGet(request:HttpRequestBuilder)=request.method==HttpMethod.Get && request.url.build().encodedPath.matches(Regex("/support/cases/\\d+"))

    @Test fun postSendsOnlyBodyAndPatchOnlyStatus()=runTest {
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);val bodies=mutableListOf<Pair<HttpMethod,JsonObject>>()
        client.plugin(HttpSend).intercept {request ->
            if(isPost(request) || request.method==HttpMethod.Patch) bodies.add(request.method to bodyOf(request))
            execute(request)
        }
        try {
            val w=world(client);w.store.save(w.attendee)
            val case=w.repo.create(SupportCaseCreate(category="payment",subject="Body",message="First",eventId=101))
            val sent=w.repo.send(case.id,"  Second  ");assertEquals("Second",sent.body);assertEquals(101,sent.authorUserId)
            assertEquals(403,assertFailsWith<SupportApiException> {w.repo.changeStatus(case.id,"resolved")}.statusCode)
            w.store.save(w.organizer)
            val changed=w.repo.changeStatus(case.id,"in_progress");assertEquals("in_progress",changed.case.status);assertTrue(changed.includesMessages)
            assertEquals(422,assertFailsWith<SupportApiException> {w.repo.changeStatus(case.id,"closed")}.statusCode)
            assertEquals(422,assertFailsWith<SupportApiException> {w.repo.send(case.id,"   ")}.statusCode)
            assertEquals(listOf(setOf("body"),setOf("status"),setOf("status"),setOf("status"),setOf("body")),bodies.map {it.second.keys})
            assertEquals(listOf(HttpMethod.Post,HttpMethod.Patch,HttpMethod.Patch,HttpMethod.Patch,HttpMethod.Post),bodies.map {it.first})
        } finally {client.close()}
    }

    @Test fun created201IsNotDuplicatedAndDraftClearsOnlyAfterConfirmation()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);val gate=CompletableDeferred<Unit>();var holdPost=false;var failGets=false;var posts=0
        client.plugin(HttpSend).intercept {request ->
            if(isPost(request)) posts++
            if(failGets && isCaseGet(request)) throw IOException("read after send fails")
            val response=execute(request)
            if(holdPost && isPost(request)) withContext(NonCancellable) {gate.await()}
            response
        }
        val w=world(client);w.store.save(w.attendee)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            val case=w.repo.create(SupportCaseCreate(category="payment",subject="Dup",message="First",eventId=101))
            runCurrent();vm.open();vm.state.first {!it.loading};vm.select(case.id);vm.state.first {it.detail!=null && !it.loading}
            vm.updateDraft(" Hello ");holdPost=true;vm.send();vm.send();runCurrent()
            assertEquals(1,posts);assertTrue(vm.state.value.sending);assertEquals(" Hello ",vm.state.value.draft)
            // A refresh while the POST answer is in flight already returns the new message.
            vm.reload();vm.state.first {!it.loading && it.detail?.messages?.size==2}
            failGets=true;gate.complete(Unit);vm.state.first {!it.sending}
            val ids=vm.state.value.detail!!.messages.map {it.id}
            assertEquals(2,ids.size);assertEquals(ids.distinct(),ids)
            assertEquals("",vm.state.value.draft);assertFalse(vm.state.value.sendUnknown)
        } finally {gate.complete(Unit);vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun serverErrorKeepsDraftAndNotFoundMarksCaseUnavailable()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);var forgeId:Int?=null
        client.plugin(HttpSend).intercept {request ->
            forgeId?.let {id -> if(isPost(request)) request.url.encodedPath="/support/cases/$id/messages"}
            execute(request)
        }
        val w=world(client);w.store.save(w.attendee)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            val case=w.repo.create(SupportCaseCreate(category="payment",subject="Err",message="First",eventId=101))
            runCurrent();vm.select(case.id);vm.state.first {it.detail!=null && !it.loading}
            vm.updateDraft("   ");vm.send();assertTrue(vm.state.value.invalidDraft)
            vm.updateDraft("x".repeat(5000));vm.send();vm.state.first {!it.sending};assertNull(vm.state.value.sendError);assertEquals("",vm.state.value.draft)
            forgeId=99999;vm.updateDraft("Keep me");vm.send();vm.state.first {!it.sending}
            assertEquals(SupportFailure(404,"SupportCaseNotFoundError","Support Case Not Found"),vm.state.value.sendError)
            assertTrue(vm.state.value.unavailable);assertEquals("Keep me",vm.state.value.draft);assertFalse(vm.state.value.sendUnknown)
        } finally {vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun requesterReplyToResolvedCaseBecomesOpenAfterReread()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true)
        val w=world(client);w.store.save(w.attendee)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            val case=w.repo.create(SupportCaseCreate(category="payment",subject="Reopen",message="First",eventId=102))
            w.store.save(w.organizer);w.repo.send(case.id,"Handled");assertEquals("open",w.repo.get(case.id).status)
            assertEquals("resolved",w.repo.changeStatus(case.id,"resolved").case.status)
            assertEquals("resolved",w.repo.changeStatus(case.id,"resolved").case.status)
            w.repo.send(case.id,"Handler note");assertEquals("resolved",w.repo.get(case.id).status)
            w.store.save(w.attendee);runCurrent()
            vm.open();vm.state.first {!it.loading};vm.select(case.id);vm.state.first {it.detail!=null && !it.loading}
            assertEquals("resolved",vm.state.value.detail!!.status);assertFalse(vm.state.value.canManageStatus)
            vm.updateDraft("Still broken");vm.send();vm.state.first {!it.sending};vm.state.first {it.detail?.status=="open"}
            assertEquals(listOf(SupportAuthor.Me,SupportAuthor.Support,SupportAuthor.Support,SupportAuthor.Me),
                vm.state.value.detail!!.messages.map {supportAuthor(it.authorUserId,vm.state.value.detail!!.requesterUserId,vm.state.value.myId)})
            vm.back();vm.state.first {!it.loading};assertEquals("open",vm.state.value.items.single {it.id==case.id}.status)
        } finally {vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun unknownSendNeverRepostsAndChecksForANewMessageOfMine()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);var mode="";var posts=0
        client.plugin(HttpSend).intercept {request ->
            if(isPost(request)) {
                posts++
                when(mode) {
                    "lost-before" -> throw IOException("not delivered")
                    "lost-after" -> {execute(request);throw IOException("delivered, answer lost")}
                    "all-down" -> throw IOException("down")
                }
            }
            if(mode=="all-down" && isCaseGet(request)) throw IOException("down")
            execute(request)
        }
        val w=world(client);w.store.save(w.attendee)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            val case=w.repo.create(SupportCaseCreate(category="payment",subject="Unknown",message="Same text",eventId=101))
            runCurrent();vm.select(case.id);vm.state.first {it.detail!=null && !it.loading}
            // Not delivered: an older message with the same body and my id does not count, the draft stays.
            mode="lost-before";vm.updateDraft("Same text");vm.send();vm.state.first {!it.sending}
            assertEquals(1,posts);assertTrue(vm.state.value.sendUnknown);assertEquals("Same text",vm.state.value.draft)
            // Delivered: the new id with my author id and the trimmed body is found, the draft clears.
            mode="lost-after";vm.updateDraft(" Delivered ");vm.send();vm.state.first {!it.sending}
            assertEquals(2,posts);assertFalse(vm.state.value.sendUnknown);assertEquals("",vm.state.value.draft)
            assertEquals("Delivered",vm.state.value.detail!!.messages.last().body)
            // Neither POST nor GET answers: warning, draft kept, manual refresh possible.
            mode="all-down";vm.updateDraft("Offline");vm.send();vm.state.first {!it.sending}
            assertEquals(3,posts);assertTrue(vm.state.value.sendUnknown);assertEquals("Offline",vm.state.value.draft);assertNotNull(vm.state.value.error)
            runCurrent();assertEquals(3,posts)
            mode="";vm.reload();vm.state.first {!it.loading};assertNull(vm.state.value.error)
        } finally {vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun ownerRequesterRetainsHandlerRightsAndForbiddenHidesThem()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);var patchAs:String?=null;var patches=0
        client.plugin(HttpSend).intercept {request ->
            if(request.method==HttpMethod.Patch) {patches++;patchAs?.let {request.headers[HttpHeaders.Authorization]="Bearer $it"}}
            execute(request)
        }
        val w=world(client)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            // The organizer opens a case about their own event 101 as its requester.
            val own=w.repo.create(SupportCaseCreate(category="payment",subject="Own",message="Mine",eventId=101))
            w.store.save(w.attendee)
            val attendeeCase=w.repo.create(SupportCaseCreate(category="payment",subject="Theirs",message="Help",eventId=101))
            runCurrent();vm.select(attendeeCase.id);vm.state.first {it.detail!=null && !it.loading};assertFalse(vm.state.value.canManageStatus)
            w.store.save(w.organizer);runCurrent()
            vm.openEvent(101,"Event");vm.state.first {!it.loading};assertEquals(setOf(own.id,attendeeCase.id),vm.state.value.items.map {it.id}.toSet())
            vm.select(own.id);vm.state.first {it.detail!=null && !it.loading};assertTrue(vm.state.value.canManageStatus)
            vm.changeStatus("resolved");vm.state.first {!it.statusBusy};assertEquals(1,patches)
            vm.back();vm.state.first {!it.loading};vm.open();vm.state.first {!it.loading}
            vm.select(attendeeCase.id);vm.state.first {it.detail!=null && !it.loading};assertTrue(vm.state.value.canManageStatus)
            vm.openEvent(101,"Event");vm.state.first {!it.loading};vm.select(attendeeCase.id);vm.state.first {it.detail!=null && !it.loading}
            assertTrue(vm.state.value.canManageStatus)
            vm.changeStatus("waiting_for_customer");vm.changeStatus("resolved");vm.state.first {!it.statusBusy}
            assertEquals(2,patches);assertEquals("waiting_for_customer",vm.state.value.detail!!.status)
            vm.changeStatus("resolved");vm.state.first {!it.statusBusy};assertEquals("resolved",vm.state.value.detail!!.status)
            // The server answers 403 SupportCaseAccessDeniedError: controls disappear and the case is re-read.
            patchAs=w.attendee.accessToken;vm.changeStatus("open");vm.state.first {!it.statusBusy};vm.state.first {!it.loading}
            assertTrue(vm.state.value.statusDenied);assertFalse(vm.state.value.canManageStatus);assertEquals("resolved",vm.state.value.detail!!.status)
            assertEquals(4,patches)
        } finally {vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun unknownStatusResultIsNotRetriedAndShowsServerStatus()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);var lose=false;var patches=0
        client.plugin(HttpSend).intercept {request ->
            if(request.method==HttpMethod.Patch) {patches++;if(lose) {execute(request);throw IOException("status answer lost")}}
            execute(request)
        }
        val w=world(client);w.store.save(w.attendee)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            val case=w.repo.create(SupportCaseCreate(category="payment",subject="Lost status",message="Help",eventId=102))
            w.store.save(w.organizer);runCurrent()
            vm.openEvent(102,"Event");vm.state.first {!it.loading};vm.select(case.id);vm.state.first {it.detail!=null && !it.loading}
            lose=true;vm.changeStatus("in_progress");vm.state.first {!it.statusBusy};vm.state.first {it.detail?.status=="in_progress"}
            assertTrue(vm.state.value.statusUnknown);runCurrent();assertEquals(1,patches)
        } finally {vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun authorLabelsDependOnlyOnIds() {
        assertEquals(SupportAuthor.Me,supportAuthor(7,7,7))
        assertEquals(SupportAuthor.Me,supportAuthor(9,7,9))
        assertEquals(SupportAuthor.Requester,supportAuthor(7,7,9))
        assertEquals(SupportAuthor.Support,supportAuthor(8,7,7))
        assertEquals(SupportAuthor.Support,supportAuthor(8,7,null))
        assertEquals(SupportAuthor.Requester,supportAuthor(7,7,null))
        assertEquals(SupportAuthor.Deleted,supportAuthor(null,7,7))
    }

    @Test fun lateAnswersFromAnotherCaseAccountOrOlderReadAreIgnored()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true);var gate=CompletableDeferred<Unit>();var holdPath:String?=null
        client.plugin(HttpSend).intercept {request ->
            val path=request.url.build().encodedPath
            val held=holdPath==path && request.method==HttpMethod.Get
            if(held) holdPath=null
            val response=execute(request)
            if(held) withContext(NonCancellable) {gate.await()}
            response
        }
        val w=world(client);w.store.save(w.attendee)
        val vm=SupportCasesViewModel(client,w.requests,w.store,"http://test")
        try {
            val a=w.repo.create(SupportCaseCreate(category="payment",subject="A",message="A1",eventId=101))
            val b=w.repo.create(SupportCaseCreate(category="payment",subject="B",message="B1",eventId=101))
            runCurrent()
            // Another case: A's answer arrives after B was opened.
            holdPath="/support/cases/${a.id}";vm.select(a.id);runCurrent();vm.select(b.id);vm.state.first {it.detail!=null && !it.loading}
            gate.complete(Unit);runCurrent();assertEquals(b.id,vm.state.value.detail!!.id)
            // Older read: the first GET (one message) is held while a newer GET (two messages) is applied.
            gate=CompletableDeferred();holdPath="/support/cases/${b.id}";vm.reload();runCurrent()
            w.repo.send(b.id,"B2");vm.reload();vm.state.first {it.detail?.messages?.size==2 && !it.loading}
            gate.complete(Unit);runCurrent();assertEquals(2,vm.state.value.detail!!.messages.size)
            // Previous account: a held answer is not applied after the account changes, and drafts are dropped.
            vm.updateDraft("Private draft")
            gate=CompletableDeferred();holdPath="/support/cases/${b.id}";vm.reload();runCurrent()
            w.store.save(w.organizer);runCurrent();gate.complete(Unit);runCurrent()
            assertNull(vm.state.value.detail);assertFalse(vm.state.value.open)
            w.store.save(w.attendee);runCurrent();vm.select(b.id);vm.state.first {it.detail!=null && !it.loading};assertEquals("",vm.state.value.draft)
        } finally {gate.complete(Unit);vm.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }

    @Test fun errorsParseCurrentBodyAndOlderDetail() {
        val current=parseSupportError(404,"""{"error":"SupportCaseNotFoundError","message":"Support Case Not Found","fields":null}""")
        assertEquals(404,current.statusCode);assertEquals("SupportCaseNotFoundError",current.code);assertEquals("Support Case Not Found",current.message)
        val validation=parseSupportError(422,"""{"error":"VALIDATION_ERROR","message":"Invalid data","fields":{"body":"too short"}}""")
        assertEquals("VALIDATION_ERROR",validation.code);assertEquals(setOf("body"),validation.fields)
        val olderCode=parseSupportError(403,"""{"detail":"SupportCaseAccessDeniedError"}""")
        assertEquals("SupportCaseAccessDeniedError",olderCode.code);assertEquals("SupportCaseAccessDeniedError",olderCode.message)
        val olderText=parseSupportError(404,"""{"detail":"Not Found"}""")
        assertNull(olderText.code);assertEquals("Not Found",olderText.message)
        val olderValidation=parseSupportError(422,"""{"detail":[{"loc":["body","status"],"msg":"bad","type":"literal_error"}]}""")
        assertEquals("VALIDATION_ERROR",olderValidation.code);assertEquals(setOf("status"),olderValidation.fields)
        assertEquals("HTTP 502",parseSupportError(502,"<html>").message)
    }
}
