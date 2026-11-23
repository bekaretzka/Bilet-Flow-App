package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.SessionStore
import com.example.biletflow.presentation.organizer.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QrScannerTest {
    @Test fun requiresRemovalAndDoesNotCountPausedTimeAsAbsence() {
        val gate = QrFrameGate()
        assertEquals("Raw+Код ", gate.accept(listOf("Raw+Код "), 0))
        assertNull(gate.accept(listOf("Raw+Код "), 5000))
        assertNull(gate.accept(emptyList(), 5100))
        assertNull(gate.accept(listOf("Raw+Код "), 5200)) // A missed frame must not re-arm it.
        assertNull(gate.accept(emptyList(), 6000))
        gate.resume()
        assertNull(gate.accept(emptyList(), 10000))
        assertNull(gate.accept(listOf("Raw+Код "), 10100))
        assertNull(gate.accept(emptyList(), 11000))
        assertNull(gate.accept(emptyList(), 11800))
        assertEquals("Raw+Код ", gate.accept(listOf("Raw+Код "), 11900))
        assertEquals("Different", gate.accept(listOf("Raw+Код ", "Different"), 12000))
        assertNull(gate.accept(listOf("Raw+Код ", "Different"), 13000))
    }

    @Test fun framesLifecycleUnknownAndOldCallbacks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val posts = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val unknown = " Unknown+Код "
        val client = HttpClient(MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.method == HttpMethod.Post) {
                val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals(setOf("code"), body.keys)
                val code = body.getValue("code").jsonPrimitive.content
                posts.add(code); gate?.await()
                if (code == unknown) throw kotlinx.io.IOException("Response lost after processing")
                respond("""{"result":"checked_in","message":"OK","ticket":null}""", HttpStatusCode.OK, jsonHeaders)
            } else if (request.url.encodedPath.endsWith("/stats")) {
                respond("""{"registered":1,"checked_in":1,"not_checked_in":0,"cancelled":0,"refunded":0}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                assertEquals(unknown, request.url.parameters["q"])
                respond(buildJsonArray { add(buildJsonObject {
                    put("id", 1); put("code", unknown); put("attendee_name", "Name"); put("ticket_type_name", "Type")
                    put("status", "checked_in"); put("checked_in_at", "2026-11-23T12:00:00+05:00")
                }) }.toString(), HttpStatusCode.OK, jsonHeaders)
            }
        }) { install(ContentNegotiation) { json() } }
        val session = SessionStore().apply { save(AuthTokens("access", "refresh", "bearer")) }
        val requests = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client, "http://test"), session, backgroundScope)
        val vm = CheckInOverviewViewModel(client, requests, session, "http://test")
        val event = Event(101, 31, "Event", startTime = "2027-01-01T10:00:00+05:00", endTime = "2027-01-01T12:00:00+05:00", status = "published", visibility = "public")
        suspend fun settled() { vm.state.first { !it.checkingIn && !it.loadingStats && !it.searching } }
        try {
            runCurrent(); vm.open(event); settled(); vm.openScanner(); vm.scannerForeground(true)
            val epoch = vm.state.value.scannerEpoch
            repeat(30) { vm.scannerFrame(epoch, listOf("MiXeD+Код ")) }
            settled(); assertEquals(listOf("MiXeD+Код "), posts)
            vm.scannerForeground(false); vm.scannerForeground(true)
            vm.scannerFrame(vm.state.value.scannerEpoch, listOf("Another")); runCurrent(); assertEquals(1, posts.size)
            vm.nextScannedTicket()
            vm.scannerFrame(epoch, listOf("Old callback"))
            vm.scannerFrame(vm.state.value.scannerEpoch, listOf("MiXeD+Код ")); runCurrent(); assertEquals(1, posts.size)
            vm.scannerForeground(false)
            vm.scannerFrame(vm.state.value.scannerEpoch, listOf("Background")); runCurrent(); assertEquals(1, posts.size)
            vm.scannerForeground(true)
            vm.scannerFrame(vm.state.value.scannerEpoch, listOf(unknown)); settled()
            assertEquals(unknown, vm.state.value.unknownCode)
            vm.nextScannedTicket(); vm.scannerFrame(vm.state.value.scannerEpoch, listOf("Blocked")); runCurrent(); assertEquals(2, posts.size)
            vm.verifyUnknown(); settled(); assertNull(vm.state.value.unknownCode); assertTrue(vm.state.value.verifiedAfterUnknown)
            vm.scannerFrame(vm.state.value.scannerEpoch, listOf("Still paused")); runCurrent(); assertEquals(2, posts.size)
            vm.nextScannedTicket(); gate = CompletableDeferred()
            val oldEpoch = vm.state.value.scannerEpoch
            vm.scannerFrame(oldEpoch, listOf("Delayed")); runCurrent()
            vm.open(event.copy(id = 102)); settled(); vm.openScanner(); vm.scannerForeground(true)
            gate.complete(Unit); runCurrent(); assertNull(vm.state.value.checkInResult)
            vm.scannerFrame(oldEpoch, listOf("Old event")); runCurrent(); assertEquals(3, posts.size)
            session.clear(); runCurrent()
            vm.scannerFrame(vm.state.value.scannerEpoch, listOf("After logout")); runCurrent()
            assertFalse(vm.state.value.scannerOpen); assertEquals(3, posts.size)
        } finally { vm.viewModelScope.cancel(); runCurrent(); client.close(); Dispatchers.resetMain() }
    }
}
