package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.createMockEventsHttpClient
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EventSettingsCopyTest {
    @Test fun publishedAndCancelledSettingsCreateIndependentDrafts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createMockEventsHttpClient()
        val payloads = mutableListOf<JsonObject>()
        var hold: CompletableDeferred<Unit>? = null
        var failBeforeSend = false
        var loseResponse = false
        client.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.build().encodedPath == "/events") {
                payloads.add(Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject)
                hold?.await()
                if (failBeforeSend) throw kotlinx.io.IOException("Mock connection unavailable")
                val response = execute(request)
                if (loseResponse) throw kotlinx.io.IOException("Response lost after draft saved")
                response
            } else execute(request)
        }
        val session = SessionStore().apply { save(AuthTokens("mock-access-token", "mock-refresh-token", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val organizer = OrganizerViewModel(client, requests, session, "http://test")
        val events = MyEventsViewModel(client, requests, session, "http://test")
        val form = CreateEventViewModel(client, requests, session, "http://test")
        val types = CreateTicketTypeViewModel(client, requests, session, "http://test")
        fun dates() {
            form.update("start_time", "2027-06-01T19:00:00+05:00"); form.update("end_time", "2027-06-01T22:00:00+05:00")
            form.update("registration_opens_at", "2027-05-01T09:00:00+05:00"); form.update("registration_closes_at", "2027-06-01T18:00:00+05:00")
        }
        try {
            runCurrent(); organizer.openForm(); organizer.apply(); organizer.state.first { !it.loading }
            repeat(3) { organizer.load(); organizer.state.first { !it.loading } }
            events.open(); events.state.first { !it.loading }; events.reload(); events.state.first { !it.loading }
            val registration = OrdersRepositoryImpl(client, "http://test").createOrder(102, "Original attendee", listOf(CreateOrderItem(2001, 1)), "mock-access-token")
            assertEquals("confirmed", registration.status)
            assertEquals(201, client.post("http://test/events/102/staff") {
                bearerAuth("mock-access-token"); contentType(ContentType.Application.Json); setBody("""{"email":"empty@biletflow.kz"}""")
            }.status.value)
            val sources = events.state.value.events.filter { it.status in listOf("published", "cancelled") }
            assertEquals(2, sources.size)
            for (source in sources) {
                events.select(source.id); events.state.first { !it.loading }
                form.copySettings(source)
                assertEquals(source.id, form.state.value.copyingFromId); assertNull(form.state.value.editingId)
                assertEquals(source.title, form.state.value.values["title"])
                assertEquals(source.description.orEmpty(), form.state.value.values["description"])
                assertEquals(source.category.orEmpty(), form.state.value.values["category"])
                assertEquals(source.venueName.orEmpty(), form.state.value.values["venue_name"])
                assertEquals(source.venueAddress.orEmpty(), form.state.value.values["venue_address"])
                assertEquals(source.capacity?.toString().orEmpty(), form.state.value.values["capacity"])
                assertEquals(source.visibility, form.state.value.values["visibility"])
                assertEquals(source.refundPolicy.orEmpty(), form.state.value.values["refund_policy"])
                assertTrue(eventDateFields.all { form.state.value.values[it].isNullOrEmpty() })
                val before = payloads.size
                form.close(); assertEquals(source.id, events.state.value.selectedId)
                assertEquals(before, payloads.size)
                form.copySettings(source); form.save(); assertEquals("required", form.state.value.errors["start_time"])
                assertEquals(before, payloads.size)
                dates(); form.update("capacity", "0"); form.save(); assertEquals("number", form.state.value.errors["capacity"])
                form.update("capacity", source.capacity?.toString().orEmpty())
                form.save(); form.save(); form.state.first { !it.saving }
                val created = requireNotNull(form.state.value.created)
                assertEquals(before + 1, payloads.size)
                assertEquals("draft", created.status); assertNotEquals(source.id, created.id)
                assertEquals(source.title, created.title); assertEquals(source.visibility, created.visibility)
                assertEquals("2027-06-01T19:00:00+05:00", created.startTime)
                assertTrue(payloads.last().keys.all { it in eventTextFields + eventDateFields + "visibility" })
                assertFalse("id" in payloads.last()); assertFalse("status" in payloads.last()); assertFalse("organizer_id" in payloads.last())
                events.filter("cancelled"); events.openCreated(created); events.state.first { !it.loading }
                assertEquals(created.id, events.state.value.selectedId); assertNull(events.state.value.filter)
                assertEquals(source, events.state.value.events.first { it.id == source.id })
                assertEquals(created, events.state.value.events.first { it.id == created.id })
                // Owner-only reads: the draft has no attendees, staff, orders, ticket types or activation.
                assertEquals("[]", client.get("http://test/events/${created.id}/attendees") { bearerAuth("mock-access-token") }.bodyAsText())
                assertEquals("[]", client.get("http://test/events/${created.id}/staff") { bearerAuth("mock-access-token") }.bodyAsText())
                val sales = Json.parseToJsonElement(client.get("http://test/events/${created.id}/sales") { bearerAuth("mock-access-token") }.bodyAsText()).jsonObject
                assertEquals(0, sales.getValue("paid_orders").jsonPrimitive.int)
                assertTrue(sales.getValue("ticket_types").jsonArray.isEmpty())
                val paid = Json.parseToJsonElement(client.get("http://test/events/${created.id}/paid-sales") { bearerAuth("mock-access-token") }.bodyAsText()).jsonObject
                assertEquals("not_started", paid.getValue("status").jsonPrimitive.content)
                assertEquals("false", paid.getValue("checklist").jsonObject.getValue("terms_accepted").jsonPrimitive.content)
                assertEquals("false", paid.getValue("checklist").jsonObject.getValue("fee_paid").jsonPrimitive.content)
                assertTrue(types.list(created.id).isEmpty())
                if (source.id == 102) {
                    assertFalse(Json.parseToJsonElement(client.get("http://test/events/102/attendees") { bearerAuth("mock-access-token") }.bodyAsText()).jsonArray.isEmpty())
                    assertEquals(1, Json.parseToJsonElement(client.get("http://test/events/102/staff") { bearerAuth("mock-access-token") }.bodyAsText()).jsonArray.size)
                }
                form.reset()
            }
            val source = sources.first()
            form.copySettings(source); dates(); form.update("title", "Kept after interruption")
            failBeforeSend = true
            form.save(); form.state.first { !it.saving }
            assertTrue(form.state.value.unknown); assertEquals("Kept after interruption", form.state.value.values["title"])
            val interruptedCount = payloads.size
            form.save(); runCurrent(); assertEquals(interruptedCount, payloads.size)
            failBeforeSend = false; loseResponse = true
            form.close(); form.copySettings(source); dates(); form.update("title", "Draft with lost confirmation")
            form.save(); form.state.first { !it.saving }; assertTrue(form.state.value.unknown)
            val lostCount = payloads.size; form.save(); runCurrent(); assertEquals(lostCount, payloads.size)
            events.backToList(); events.state.first { !it.loading }
            assertEquals(1, events.state.value.events.count { it.title == "Draft with lost confirmation" })
            loseResponse = false
            form.reset(); form.copySettings(source); dates(); hold = CompletableDeferred()
            form.save(); runCurrent(); assertTrue(form.state.value.saving)
            session.clear(); session.save(AuthTokens("mock-empty-access-token", "mock-empty-refresh-token", "bearer")); runCurrent()
            hold.complete(Unit); runCurrent()
            assertFalse(form.state.value.open); assertNull(form.state.value.created); assertNull(form.state.value.copyingFromId)
        } finally {
            organizer.viewModelScope.cancel(); events.viewModelScope.cancel(); form.viewModelScope.cancel(); types.viewModelScope.cancel()
            runCurrent(); client.close(); Dispatchers.resetMain()
        }
    }
}
