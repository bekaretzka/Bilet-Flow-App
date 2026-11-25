package com.example.biletflow
import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CampaignsTest {
    private fun input(code:String?="SAVE")=CampaignCreate("Campaign",code,"percent",25)
    @Test fun currentArrayContractOptionalFieldsCodesPaidTypesAndAccess()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient();val store=SessionStore().apply {save(AuthTokens("mock-access-token","mock-refresh-token","bearer"))}
        val auth=AuthRepositoryImpl(client,"http://test");val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val repo=CampaignsRepository(client,requests,"http://test");val organizer=OrganizerViewModel(client,requests,store,"http://test")
        client.plugin(HttpSend).intercept {request ->assertFalse(request.url.build().encodedPath.startsWith("/organizer/"));assertNull(request.url.parameters["cursor"]);execute(request)}
        try {
            runCurrent();organizer.openForm();organizer.apply();organizer.state.first {!it.loading};repeat(3) {organizer.load();organizer.state.first {!it.loading}}
            assertTrue(repo.list(101).isEmpty())
            val percent=repo.create(101,input().copy(ticketTypeIds=listOf(1001)))
            assertEquals(25L,percent.discountValue);assertNull(percent.startsAt);assertNull(percent.endsAt);assertNull(percent.maxRedemptions)
            assertTrue(percent.isActive);assertEquals(0L,percent.redemptionsCount);assertEquals(0L,percent.linkOpens);assertTrue(percent.link.contains("/events/101?campaign="));assertEquals(0L,percent.stats.net)
            val fixed=repo.create(101,input(null).copy(discountType="fixed",discountValue=2_000_000_000,maxRedemptions=2_000_001))
            assertEquals(8,fixed.code.length);assertTrue(Regex("[A-Z0-9-]{3,32}").matches(fixed.code));assertEquals(2_000_001L,fixed.maxRedemptions);assertTrue(fixed.ticketTypeIds.isEmpty())
            assertEquals("PromoCodeAlreadyExistsError",assertFailsWith<CampaignApiException> {repo.create(101,input("save"))}.code)
            assertEquals("SAVE",repo.create(102,input("save")).code)
            for(bad in listOf(input("BAD_CODE"),input("AA"),input().copy(discountValue=101),input().copy(maxRedemptions=0),input().copy(discountType="fixed",discountValue=0),input().copy(startsAt="2027-02-01T10:00:00Z",endsAt="2027-01-01T10:00:00Z"))) {
                assertEquals(422,assertFailsWith<CampaignApiException> {repo.create(101,bad)}.status)
            }
            for(type in listOf(1002,2002)) assertEquals("InvalidCampaignError",assertFailsWith<CampaignApiException> {repo.create(101,input("TYPE-$type").copy(ticketTypeIds=listOf(type)))}.code)
            store.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"));assertEquals(2,repo.list(101).size)
            assertTrue(store.tokens.value!!.accessToken.startsWith("mock-rotated-access"))
            store.save(auth.login("empty@biletflow.kz","password123"));assertFailsWith<CampaignApiException> {repo.list(101)}
        } finally {organizer.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
    @Test fun retryDoubleClickUnknownResultAndSessionIsolation()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient();val store=SessionStore().apply {save(AuthTokens("mock-access-token","mock-refresh-token","bearer"))}
        val auth=AuthRepositoryImpl(client,"http://test");val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
        val organizer=OrganizerViewModel(client,requests,store,"http://test");val vm=CampaignsViewModel(client,requests,store,"http://test")
        var fail=true;var posts=0;val gate=CompletableDeferred<Unit>()
        client.plugin(HttpSend).intercept {request ->
            if(request.url.build().encodedPath.endsWith("/campaigns")) {
                if(request.method==HttpMethod.Get && fail) {fail=false;throw kotlinx.io.IOException("List offline")}
                if(request.method==HttpMethod.Post) {posts++;val body=Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject;assertFalse("client_request_id" in body);assertFalse("usage_limit" in body);gate.await()}
            };execute(request)
        }
        try {
            runCurrent();organizer.openForm();organizer.apply();organizer.state.first {!it.loading};repeat(3) {organizer.load();organizer.state.first {!it.loading}}
            vm.open(101,"Event");vm.state.first {!it.loading};assertNotNull(vm.state.value.error);vm.retry();vm.state.first {!it.loading};assertNull(vm.state.value.error)
            vm.createForm();vm.state.first {!it.typesLoading};assertTrue(vm.state.value.types.all {it.price>0});vm.toggle(1002);assertTrue(vm.state.value.selected.isEmpty())
            vm.update("name","campaign-unknown");vm.update("discount_value","20");vm.save();vm.save();runCurrent();assertEquals(1,posts)
            gate.complete(Unit);vm.state.first {!it.saving};assertTrue(vm.state.value.unknown);assertEquals("campaign-unknown",vm.state.value.values["name"])
            // 53.1: after an unknown outcome the list is re-read once; the generated-code campaign is found by new id + name + discount, without a second POST.
            vm.save();runCurrent();assertEquals(1,posts);vm.state.first {!it.reconciling};vm.reload();vm.state.first {!it.loading};assertEquals(1,vm.state.value.items.size)
            assertEquals(1,posts);assertFalse(vm.state.value.unknown);assertEquals("campaign-unknown",vm.state.value.detail?.name)
            store.save(auth.login("empty@biletflow.kz","password123"));runCurrent();assertNull(vm.state.value.eventId);assertTrue(vm.state.value.items.isEmpty());assertTrue(vm.state.value.values.isEmpty())
        } finally {gate.complete(Unit);vm.viewModelScope.cancel();organizer.viewModelScope.cancel();client.close();Dispatchers.resetMain()}
    }
    @Test fun suspensionEndsSession()=runTest {
        val client=createMockEventsHttpClient()
        try {
            val auth=AuthRepositoryImpl(client,"http://test");auth.register("suspend-on-campaigns@biletflow.kz","password123")
            val store=SessionStore().apply {save(auth.login("suspend-on-campaigns@biletflow.kz","password123"))}
            val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,store,backgroundScope)
            assertFailsWith<OrdersApiException> {CampaignsRepository(client,requests,"http://test").list(101)};assertNull(store.tokens.value);assertTrue(store.suspended.value)
        } finally {client.close()}
    }
}
