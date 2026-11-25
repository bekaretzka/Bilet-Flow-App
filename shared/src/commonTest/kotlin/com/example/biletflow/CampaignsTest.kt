package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.CampaignsViewModel
import com.example.biletflow.presentation.organizer.OrganizerViewModel
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CampaignsTest {
    private fun input(n: Int)=CampaignCreate("Campaign $n","CODE$n","percent",25,"2027-01-01T10:00:00+05:00","2027-02-01T10:00:00+05:00",
        clientRequestId="00000000-0000-4000-8000-${n.toString().padStart(12,'0')}")
    @Test fun contractDiscountsValidationIdempotencyPaginationRefreshAndOwnership() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val session=SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val repo=CampaignsRepository(client,requests,"http://test")
        val organizer=OrganizerViewModel(client,requests,session,"http://test")
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            assertTrue(repo.list(101).items.isEmpty())
            val percent=repo.create(101,input(1).copy(ticketTypeIds=listOf(1001)))
            assertEquals(25L,percent.discountValue); assertEquals("percent",percent.discountType)
            assertEquals(percent,repo.create(101,input(1).copy(ticketTypeIds=listOf(1001))))
            val fixed=repo.create(101,input(2).copy(discountType="fixed",discountValue=500,usageLimit=10))
            assertEquals(500L,fixed.discountValue); assertEquals(10,fixed.usageLimit); assertTrue(fixed.ticketTypeIds.isEmpty())
            assertEquals(409,assertFailsWith<CampaignApiException> { repo.create(101,input(1).copy(name="Changed")) }.status)
            assertEquals("CampaignCodeAlreadyExistsError",assertFailsWith<CampaignApiException> { repo.create(102,input(3).copy(code="code1")) }.code)
            for(invalid in listOf(input(4).copy(discountValue=101),input(4).copy(discountType="fixed",discountValue=0),input(4).copy(usageLimit=0),input(4).copy(endsAt=input(4).startsAt),input(4).copy(startsAt="2027-01-01T10:00:00"),input(4).copy(ticketTypeIds=listOf(2001)))) {
                assertEquals(422,assertFailsWith<CampaignApiException> { repo.create(101,invalid) }.status)
            }
            assertFails { repo.create(101,input(5).copy(name="campaign-unknown")) }
            val recovered=repo.create(101,input(5).copy(name="campaign-unknown"))
            assertEquals(recovered,repo.create(101,input(5).copy(name="campaign-unknown")))
            val page1=repo.list(101,limit=1); val page2=repo.list(101,page1.nextCursor,1); val page3=repo.list(101,page2.nextCursor,1)
            assertEquals(listOf(recovered.id,fixed.id,percent.id),(page1.items+page2.items+page3.items).map { it.id }); assertNull(page3.nextCursor)
            assertTrue(repo.list(102).items.isEmpty())
            session.rotateTokens(AuthTokens("expired-access","mock-refresh-token","bearer"))
            assertEquals(3,repo.list(101).items.size); assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access"))
            session.save(auth.login("empty@biletflow.kz","password123")); runCurrent()
            assertEquals(403,assertFailsWith<CampaignApiException> { repo.list(101) }.status)
            assertEquals(403,assertFailsWith<CampaignApiException> { repo.create(101,input(7)) }.status)
        } finally { organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun formLostResponseDoubleClickFieldsAndLateAccountResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val session=SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth=AuthRepositoryImpl(client,"http://test")
        val requests=SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),auth,session,backgroundScope)
        val organizer=OrganizerViewModel(client,requests,session,"http://test")
        val vm=CampaignsViewModel(client,requests,session,"http://test")
        val bodies=mutableListOf<String>()
        var gate: CompletableDeferred<Unit>?=null
        var failList=true
        client.plugin(HttpSend).intercept { request ->
            if(request.url.build().encodedPath.endsWith("/campaigns")) {
                if(request.method == HttpMethod.Get && failList) { failList=false; throw kotlinx.io.IOException("Mock offline list") }
                if(request.method == HttpMethod.Post) { bodies.add((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()); gate?.await() }
            }
            execute(request)
        }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            vm.open(101,"Event"); vm.state.first { !it.loading }; assertNotNull(vm.state.value.error)
            vm.retry(); vm.state.first { !it.loading }; assertNull(vm.state.value.error)
            vm.createForm(); vm.state.first { !it.typesLoading }; assertNull(vm.state.value.typesError)
            assertTrue(vm.state.value.types.isNotEmpty())
            vm.save(); assertTrue("name" in vm.state.value.fields); assertTrue(bodies.isEmpty())
            vm.update("name","campaign-unknown"); vm.update("code","  UI51  "); vm.update("discount_value","15")
            vm.update("starts_at","2027-01-01T10:00:00+05:00"); vm.update("ends_at","2027-02-01T10:00:00+05:00")
            vm.toggle(1001); gate=CompletableDeferred(); vm.save(); vm.save(); runCurrent(); assertEquals(1,bodies.size)
            gate!!.complete(Unit); gate=null; vm.state.first { !it.saving }
            assertTrue(vm.state.value.unknown); assertEquals("campaign-unknown",vm.state.value.values["name"])
            vm.save(); vm.state.first { !it.saving && !it.loading }
            assertEquals(bodies[0],bodies[1]); assertFalse(vm.state.value.form); assertEquals(1,vm.state.value.items.size)
            assertEquals("UI51",vm.state.value.items.single().code)
            vm.createForm(); vm.state.first { !it.typesLoading }
            vm.update("name","Late"); vm.update("code","LATE51"); vm.update("discount_value","5")
            vm.update("starts_at","2027-01-01T10:00:00+05:00"); vm.update("ends_at","2027-02-01T10:00:00+05:00")
            gate=CompletableDeferred(); vm.save(); runCurrent()
            session.save(auth.login("empty@biletflow.kz","password123")); runCurrent()
            assertNull(vm.state.value.eventId); assertTrue(vm.state.value.values.isEmpty())
            gate!!.complete(Unit); gate=null; runCurrent(); assertTrue(vm.state.value.items.isEmpty())
        } finally { gate?.complete(Unit); vm.viewModelScope.cancel(); organizer.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
