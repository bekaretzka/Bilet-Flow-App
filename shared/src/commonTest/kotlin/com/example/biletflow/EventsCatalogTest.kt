package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.*
import com.example.biletflow.data.repositories.EventsRepositoryImpl
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.repositories.EventsRepository
import com.example.biletflow.presentation.events.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EventsCatalogTest {
    @Test fun mockPagesFiltersAndCategoriesUseTheHttpDto() = runTest {
        val client=createMockEventsHttpClient(catalogPageFixtures=true)
        val repo=EventsRepositoryImpl(client,"http://test")
        try {
            val first=repo.getEventsPage(limit=20)
            val second=repo.getEventsPage(limit=20,offset=20)
            assertEquals(27,first.total); assertEquals(20,first.items.size); assertEquals(7,second.items.size)
            assertEquals(27,(first.items+second.items).map { it.id }.distinct().size)
            val found=repo.getEventsPage(q="  ДАЛЬНИЙ  ",category="Театр")
            assertEquals(1,found.total); assertEquals(10025,found.items.single().id)
            assertTrue(repo.getCategories().contains("Театр"))
            assertEquals(0,repo.getEventsPage(q="no-matches").total)
            assertEquals(0,repo.getEventsPage(q="Дальний",category="Концерт").total)
            assertTrue(repo.getEventsPage(q="Сауран").total > 0) // Address is searched by the server.
            val old=EventsRepositoryImpl(createMockEventsHttpClient(publishedFixtureEvents = true),"http://test").getEvents()
            assertEquals(3,old.size); assertNull(old.last().category); assertNull(old.last().venueName)
            assertEquals("05.12.2026 · 19:00 (UTC+05:00)",formatEventDate(old.first().startTime))
        } finally { client.close() }
    }
    @Test fun firstAndNextPageErrorsRetryTheSameOffsetAndPreserveCards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient(publishedFixtureEvents = true)
        var firstFailure=true; var nextFailure=true
        val offsets=mutableListOf<String?>()
        client.plugin(HttpSend).intercept { request ->
            if(request.url.build().encodedPath == "/events") {
                val offset=request.url.parameters["offset"]; offsets.add(offset)
                if(firstFailure) { firstFailure=false; throw kotlinx.io.IOException("first page offline") }
                if(offset == "2" && nextFailure) { nextFailure=false; throw kotlinx.io.IOException("next page offline") }
            }
            execute(request)
        }
        val vm=EventsViewModel(EventsRepositoryImpl(client,"http://test"),pageSize=2)
        try {
            vm.state.first { !it.isLoading }; assertTrue(vm.state.value.loadFailed)
            vm.reload(); vm.state.first { !it.isLoading }; assertEquals(2,vm.state.value.events.size,vm.state.value.error)
            vm.loadMore(); vm.state.first { !it.loadingMore }; assertNotNull(vm.state.value.pageError)
            assertEquals(2,vm.state.value.events.size); assertEquals(2,vm.state.value.nextOffset)
            vm.loadMore(); vm.state.first { !it.loadingMore }; assertEquals(3,vm.state.value.events.size)
            assertFalse(vm.state.value.hasMore); assertEquals(listOf<String?>("0","0","2","2"),offsets)
            vm.updateQuery("  Город  "); advanceTimeBy(301); vm.state.first { !it.isLoading }
            assertEquals(1,vm.state.value.total); assertEquals(1,vm.state.value.nextOffset)
            vm.selectCategory("Концерт"); vm.state.first { !it.isLoading }; assertTrue(vm.state.value.events.isEmpty())
            vm.selectCategory(null); vm.state.first { !it.isLoading }; assertEquals(1,vm.state.value.total)
            vm.selectCategory("Лекция"); vm.state.first { !it.isLoading }; vm.updateQuery("")
            advanceTimeBy(301); vm.state.first { !it.isLoading }; assertEquals("Лекция",vm.state.value.category)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun debounceLateResponsesAndDeduplication() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate=CompletableDeferred<Unit>(); val calls=mutableListOf<Pair<String?,Int>>()
        val event=Event(1,1,"New",startTime="2027-01-01T00:00:00Z",endTime="2027-01-02T00:00:00Z",status="published",visibility="public")
        val repo=object: EventsRepository {
            override suspend fun getEvents()=emptyList<Event>()
            override suspend fun getEvent(eventId: Int)=event
            override suspend fun getTicketTypes(eventId: Int)=emptyList<TicketType>()
            override suspend fun getEventsPage(q: String?,category: String?,limit: Int,offset: Int): EventPage {
                calls.add(q to offset)
                if(q == "Old") withContext(NonCancellable) { gate.await() }
                return if(q == "Old") EventPage(listOf(event.copy(title="Old")),1) else EventPage(listOf(event),2)
            }
        }
        val vm=EventsViewModel(repo,pageSize=1)
        try {
            runCurrent(); vm.loadMore(); runCurrent(); assertEquals(1,vm.state.value.events.size); assertFalse(vm.state.value.hasMore)
            vm.updateQuery("O"); vm.updateQuery("Old"); advanceTimeBy(301); runCurrent()
            assertFalse(calls.any { it.first == "O" })
            vm.updateQuery("New"); advanceTimeBy(301); runCurrent(); assertEquals("New",vm.state.value.events.single().title)
            gate.complete(Unit); runCurrent(); assertEquals("New",vm.state.value.events.single().title)
            assertEquals("New",vm.state.value.query)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun environmentAndServerIdentitySeparateSessions() {
        val original=NetworkSettings.current
        try {
            val mock=NetworkSettings.configure("mock")
            val a=NetworkSettings.configure("local","http://127.0.0.1:8000/")
            val b=NetworkSettings.configure("local","http://127.0.0.1:8001")
            assertNotEquals(mock.sessionNamespace,a.sessionNamespace); assertNotEquals(a.sessionNamespace,b.sessionNamespace)
            assertEquals(a.sessionNamespace,NetworkSettings.configure("local","http://127.0.0.1:8000").sessionNamespace)
            assertFails { NetworkSettings.configure("staging",stagingUrl="") }
            assertFails { NetworkSettings.configure("staging",stagingUrl="http://example.test") }
            assertEquals(ApiEnvironment.STAGING,NetworkSettings.configure("staging",stagingUrl="https://example.test").environment)
        } finally { NetworkSettings.configure(original.environment.name,original.baseUrl,original.baseUrl) }
    }
    @Test fun lateCategoriesNeverOverwriteLoadedPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate=CompletableDeferred<Unit>(); val pageGate=CompletableDeferred<Unit>()
        val event=Event(1,1,"Loaded",startTime="2027-01-01T00:00:00Z",endTime="2027-01-02T00:00:00Z",status="published",visibility="public")
        val repo=object: EventsRepository {
            override suspend fun getEvents()=listOf(event)
            override suspend fun getEvent(eventId: Int)=event
            override suspend fun getTicketTypes(eventId: Int)=emptyList<TicketType>()
            override suspend fun getCategories(): List<String> { gate.await(); return listOf("Sport") }
            override suspend fun getEventsPage(q: String?,category: String?,limit: Int,offset: Int): EventPage { pageGate.await(); return EventPage(listOf(event),1) }
        }
        val vm=EventsViewModel(repo)
        try {
            runCurrent(); pageGate.complete(Unit); runCurrent(); assertEquals(1,vm.state.value.events.size)
            gate.complete(Unit); runCurrent()
            assertEquals(1,vm.state.value.events.size); assertFalse(vm.state.value.isLoading)
            assertEquals(listOf("Sport"),vm.state.value.categories)
        } finally { pageGate.complete(Unit); gate.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
