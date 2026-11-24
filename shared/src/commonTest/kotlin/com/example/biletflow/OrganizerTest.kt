package com.example.biletflow

import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.OrganizerViewModel
import com.example.biletflow.presentation.organizer.MyEventsViewModel
import com.example.biletflow.presentation.organizer.CreateEventViewModel
import com.example.biletflow.presentation.organizer.validateEventForm
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizerTest {
    @Test fun applyRetryApprovalAndAccountIsolation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val session = SessionStore().apply { save(AuthTokens("mock-access-token","mock-refresh-token","bearer")) }
        val auth = AuthRepositoryImpl(client,"http://test")
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"), auth, session, backgroundScope)
        val vm = OrganizerViewModel(client,requests,session,"http://test")
        val events = MyEventsViewModel(client,requests,session,"http://test")
        val create = CreateEventViewModel(client, requests, session, "http://test")
        val tickets = com.example.biletflow.presentation.organizer.CreateTicketTypeViewModel(client, requests, session, "http://test")
        val visitor = com.example.biletflow.presentation.eventdetails.EventDetailsViewModel(EventsRepositoryImpl(client,"http://test"))
        try {
            runCurrent()
            vm.load(); vm.state.first { !it.loading }; assertTrue(vm.state.value.absent)
            events.open(); events.state.first { !it.loading }
            assertTrue(events.state.value.accessDenied)
            events.close()
            vm.openForm(); vm.apply(); vm.apply(); vm.state.first { !it.loading }
            assertEquals("pending",vm.state.value.profile?.status)
            events.open(); events.state.first { !it.loading }; assertTrue(events.state.value.accessDenied)
            events.close()
            vm.load(); vm.state.first { !it.loading }; assertTrue(vm.state.value.error)
            vm.load(); vm.state.first { !it.loading }; assertEquals("pending",vm.state.value.profile?.status)
            vm.load(); vm.state.first { !it.loading }; assertEquals("approved",vm.state.value.profile?.status)
            events.open(); events.state.first { !it.loading }; assertTrue(events.state.value.error)
            events.reload(); events.state.first { !it.loading }
            assertEquals(3, events.state.value.events.size)
            val published = events.state.value.events.first { it.status == "published" }
            create.edit(published)
            assertEquals(published.startTime, create.state.value.values["start_time"])
            create.update("description", "")
            create.update("title", "Updated published")
            create.save(); create.state.first { !it.saving }
            assertEquals("Updated published", create.state.value.created?.title)
            assertNull(create.state.value.created?.description)
            assertEquals(published.startTime, create.state.value.created?.startTime)
            create.reset()
            events.reload(); events.state.first { !it.loading }
            assertEquals("Updated published", events.state.value.events.first { it.id == published.id }.title)
            create.open()
            create.save()
            assertTrue(create.state.value.errors.containsKey("title"))
            create.update("title", "New draft")
            create.update("start_time", "2027-02-01T10:00:00+05:00")
            create.update("end_time", "2027-02-01T12:00:00+05:00")
            create.save(); create.save(); create.state.first { !it.saving }
            assertEquals("draft", create.state.value.created?.status)
            events.reload(); events.state.first { !it.loading }
            assertEquals(1, events.state.value.events.count { it.title == "New draft" })
            val draft = events.state.value.events.first { it.title == "New draft" }
            tickets.open(draft.id)
            tickets.save()
            assertTrue("name" in tickets.state.value.errors)
            tickets.update("name", "Free entry")
            tickets.update("price", "0")
            tickets.update("quantity", "50")
            tickets.update("sales_start", "2027-01-01T10:00:00+05:00")
            tickets.update("sales_end", "2027-02-01T10:00:00+05:00")
            tickets.save(); tickets.save(); tickets.state.first { !it.saving }
            val issued = tickets.state.value.loaded.getValue(draft.id).single()
            assertEquals(0, issued.price)
            assertEquals(10, issued.maxPerOrder)
            assertEquals(draft.id, issued.eventId)
            tickets.edit(issued)
            tickets.update("price", "2000")
            tickets.update("description", "Paid")
            tickets.save(); tickets.state.first { !it.saving }
            assertEquals(2000,tickets.list(draft.id).single().price)
            tickets.edit(tickets.list(draft.id).single())
            tickets.update("price", "0")
            tickets.update("description", "")
            tickets.update("sales_end", "2027-03-01T10:00:00+05:00")
            tickets.save(); tickets.state.first { !it.saving }
            assertEquals(0,tickets.list(draft.id).single().price)
            assertNull(tickets.list(draft.id).single().description)
            assertEquals("2027-03-01T10:00:00+05:00",tickets.list(draft.id).single().salesEnd)
            events.select(draft.id); events.state.first { !it.loading }
            events.changeStatus(true); events.state.first { !it.changingStatus }
            assertNotNull(events.state.value.statusError)
            create.edit(draft)
            create.update("venue_name", "Hall")
            create.update("venue_address", "Astana")
            create.save(); create.state.first { !it.saving }
            events.updated(requireNotNull(create.state.value.created))
            create.reset()
            events.changeStatus(true); events.state.first { !it.changingStatus }
            assertEquals("published",events.state.value.events.first { it.id == draft.id }.status)
            val publicEvents = EventsRepositoryImpl(client,"http://test")
            assertTrue(publicEvents.getEvents().any { it.id == draft.id })
            assertEquals(draft.id,publicEvents.getEvent(draft.id).id)
            assertTrue(publicEvents.getTicketTypes(draft.id).any { it.id == issued.id })
            tickets.load(draft.id); tickets.state.first { !it.listLoading }
            assertEquals(1,tickets.list(draft.id).count { it.id == issued.id })
            assertEquals(0,publicEvents.getTicketTypes(draft.id).first { it.id == issued.id }.price)
            val sold = publicEvents.getTicketTypes(101).first { it.soldCount > 0 }
            tickets.edit(sold); tickets.update("quantity","1")
            tickets.save(); tickets.state.first { !it.saving }
            assertTrue(tickets.state.value.belowSold)
            tickets.close()
            visitor.load(101)
            visitor.state.first { !it.areTicketsLoading && it.ticketTypes.isNotEmpty() }
            visitor.incrementTicket(1001)
            assertEquals(1,visitor.state.value.selectedQuantities[1001])
            val visible = publicEvents.getTicketTypes(101).first { it.id == 1001 }
            tickets.setHidden(visible,true)
            tickets.state.first { 1001 !in it.hiding }
            assertTrue(tickets.list(101).first { it.id == 1001 }.isHidden)
            assertFalse(publicEvents.getTicketTypes(101).any { it.id == 1001 })
            tickets.load(101); tickets.state.first { !it.listLoading }
            assertTrue(tickets.list(101).first { it.id == 1001 }.isHidden)
            visitor.loadTicketTypes(101)
            visitor.state.first { !it.areTicketsLoading }
            assertNull(visitor.state.value.selectedQuantities[1001])
            tickets.setHidden(tickets.list(101).first { it.id == 1001 },false)
            tickets.state.first { 1001 !in it.hiding }
            assertTrue(publicEvents.getTicketTypes(101).any { it.id == 1001 && !it.isHidden })
            events.changeStatus(false); events.state.first { !it.changingStatus }
            assertFalse(publicEvents.getEvents().any { it.id == draft.id })
            events.reload(); events.state.first { !it.loading }
            assertEquals("draft",events.state.value.events.first { it.id == draft.id }.status)
            create.edit(events.state.value.events.first { it.id == draft.id })
            create.update("title", "status-offline")
            create.save(); create.state.first { !it.saving }
            events.updated(requireNotNull(create.state.value.created)); create.reset()
            events.changeStatus(true); events.state.first { !it.changingStatus }
            assertTrue(events.state.value.statusUnknown)
            events.changeStatus(false)
            assertTrue(events.state.value.statusUnknown)
            events.reload(); events.state.first { !it.loading }
            assertFalse(events.state.value.statusUnknown)
            assertEquals("published",events.state.value.events.first { it.id == draft.id }.status)
            events.backToList(); events.state.first { !it.loading }
            events.select(draft.id); events.state.first { !it.loading }
            events.cancelEvent(); events.cancelEvent(); events.state.first { !it.changingStatus }
            assertTrue(events.state.value.statusUnknown)
            events.reload(); events.state.first { !it.loading }
            assertEquals("cancelled", events.state.value.events.first { it.id == draft.id }.status)
            assertFalse(publicEvents.getEvents().any { it.id == draft.id })
            assertFailsWith<EventsApiException> { publicEvents.getEvent(draft.id) }
            events.backToList(); events.state.first { !it.loading }
            tickets.open(published.id)
            assertTrue(tickets.state.value.loaded[published.id].isNullOrEmpty())
            tickets.close()
            create.reset()
            assertTrue(events.state.value.events.all { it.organizerId == vm.state.value.profile?.id })
            events.filter("draft")
            assertEquals(1, events.state.value.events.count { it.status == events.state.value.filter })
            events.close()
            session.save(AuthTokens("expired-access", "mock-refresh-token", "bearer"))
            runCurrent()
            vm.load(); vm.state.first { !it.loading }
            assertEquals("approved", vm.state.value.profile?.status)
            assertTrue(session.tokens.value!!.accessToken.startsWith("mock-rotated-access-"))
            session.save(AuthTokens("mock-empty-access-token","mock-empty-refresh-token","bearer"))
            runCurrent(); assertNull(vm.state.value.profile)
            assertTrue(tickets.state.value.loaded.isEmpty())
            tickets.edit(issued)
            tickets.update("price","77")
            tickets.save(); tickets.state.first { !it.saving }
            assertTrue(tickets.state.value.failed)
            assertTrue(tickets.state.value.loaded.isEmpty())
            tickets.close()
            assertTrue(events.state.value.events.isEmpty())
            vm.load(); vm.state.first { !it.loading }; assertTrue(vm.state.value.absent)
            vm.openForm(); vm.apply(); vm.state.first { !it.loading }
            repeat(3) { vm.load(); vm.state.first { !it.loading } }
            events.open(); events.state.first { !it.loading }
            events.reload(); events.state.first { !it.loading }
            assertFalse(events.state.value.error)
            assertTrue(events.state.value.events.isEmpty())
            events.select(draft.id); events.state.first { !it.loading }
            events.cancelEvent(); events.state.first { !it.changingStatus }
            assertTrue(events.state.value.accessDenied)
        } finally { visitor.viewModelScope.cancel(); tickets.viewModelScope.cancel(); create.viewModelScope.cancel(); events.viewModelScope.cancel(); vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
