package com.example.biletflow

import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class SessionRefreshTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun historyIgnoresLateResponseAfterLogoutAndAccountSwitch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = SessionStore().also { it.save(AuthTokens("old", "r", "bearer")) }
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer old") {
                release.await()
                respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            } else respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val vm = com.example.biletflow.presentation.orderhistory.OrderHistoryViewModel(OrdersRepositoryImpl(client,"http://test"), store)
            runCurrent()
            vm.open()
            runCurrent()
            store.clear()
            vm.close()
            store.save(AuthTokens("other", "r2", "bearer"))
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue(vm.state.value.orders.isEmpty())
            assertNull(vm.state.value.openedOrder)
            assertFalse(vm.state.value.isActive)
            vm.open()
            advanceUntilIdle()
            assertTrue(vm.state.value.orders.isEmpty())
            vm.close()
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test
    fun mockRefreshTokenIsSingleUse() = runTest {
        val client = com.example.biletflow.core.network.createMockEventsHttpClient()
        try {
            val auth = AuthRepositoryImpl(client,"http://test")
            val rotated = auth.refresh("mock-refresh-token")
            assertNotEquals("mock-refresh-token", rotated.refreshToken)
            assertEquals(401, assertFailsWith<AuthApiException> { auth.refresh("mock-refresh-token") }.statusCode)
            auth.refresh(rotated.refreshToken)
        } finally { client.close() }
    }
    @Test
    fun lateRefreshCannotRestoreLoggedOutOrNewSession() = runTest {
        for (switchAccount in listOf(false, true)) {
            val store = SessionStore().also { it.save(AuthTokens("old", "refresh", "bearer")) }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
                if (request.url.encodedPath == "/auth/refresh") {
                    started.complete(Unit)
                    release.await()
                    respond("""{"access_token":"late","refresh_token":"late-r","token_type":"bearer"}""", io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
                } else respond("{}",io.ktor.http.HttpStatusCode.Unauthorized)
            }) { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }
            try {
                val repo = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),store,backgroundScope)
                val request = async { runCatching { repo.getMyOrders("old") } }
                started.await()
                store.clear()
                if (switchAccount) store.save(AuthTokens("other", "other-r", "bearer"))
                release.complete(Unit)
                request.await()
                assertEquals(if (switchAccount) "other" else null, store.tokens.value?.accessToken)
            } finally { client.close() }
        }
    }
    private fun tokens(access: String = "old") = AuthTokens(access, "refresh", "bearer")

    @Test fun concurrent401UsesOneRefreshAndPreservesGeneration() = runTest {
        val store = SessionStore().also { it.save(tokens()) }
        val generation = store.generation
        var refreshes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                refreshes++
                delay(50)
                respond("""{"access_token":"new","refresh_token":"rotated","token_type":"bearer"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
            } else if (request.headers[HttpHeaders.Authorization] == "Bearer old") {
                respond("{\"detail\":\"Expired\"}", HttpStatusCode.Unauthorized)
            } else respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo = SessionOrdersRepository(OrdersRepositoryImpl(client, "http://test"), AuthRepositoryImpl(client,"http://test"), store, backgroundScope)
            val first = async { repo.getMyOrders("old") }
            val second = async { repo.getMyOrders("old") }
            assertTrue(first.await().isEmpty())
            assertTrue(second.await().isEmpty())
            assertEquals(1, refreshes)
            assertEquals(generation, store.generation)
            assertEquals("rotated", store.tokens.value?.refreshToken)
        } finally { client.close() }
    }

    @Test fun second401ClearsSession() = runTest {
        val store = SessionStore().also { it.save(tokens()) }
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") respond("""{"access_token":"new","refresh_token":"rotated","token_type":"bearer"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
            else { requests++; respond("{}",HttpStatusCode.Unauthorized) }
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),store,backgroundScope)
            assertFailsWith<OrdersApiException> { repo.getMyOrders("old") }
            assertEquals(2, requests)
            assertNull(store.tokens.value)
        } finally { client.close() }
    }

    @Test fun refreshNetworkFailureKeepsTokensAndAllowsManualRetry() = runTest {
        val store = SessionStore().also { it.save(tokens()) }
        var refreshes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") { refreshes++; throw kotlinx.io.IOException("Offline") }
            respond("{}", HttpStatusCode.Unauthorized)
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),store,backgroundScope)
            repeat(2) { assertEquals(503, assertFailsWith<OrdersApiException> { repo.getMyOrders("old") }.statusCode) }
            assertEquals(2,refreshes)
            assertEquals("old",store.tokens.value?.accessToken)
        } finally { client.close() }
    }

    @Test fun lateRefreshCannotRestoreLoggedOutOrReplacedSession() = runTest {
        for (replace in listOf(false,true)) {
            val store = SessionStore().also { it.save(tokens()) }
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val client = HttpClient(MockEngine { request ->
                if(request.url.encodedPath == "/auth/refresh") {
                    started.complete(Unit); finish.await()
                    respond("""{"access_token":"late","refresh_token":"late-refresh","token_type":"bearer"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
                } else respond("{}",HttpStatusCode.Unauthorized)
            }) { install(ContentNegotiation) { json() } }
            try {
                val repo = SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),store,backgroundScope)
                val request = async { repo.getMyOrders("old") }
                started.await()
                store.clear()
                if(replace) store.save(tokens("other"))
                finish.complete(Unit)
                assertFailsWith<CancellationException> { request.await() }
                assertEquals(if(replace) "other" else null,store.tokens.value?.accessToken)
            } finally { client.close() }
        }
    }
}
