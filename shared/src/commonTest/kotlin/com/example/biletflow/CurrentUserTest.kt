package com.example.biletflow

import androidx.lifecycle.viewModelScope
import com.example.biletflow.core.network.*
import com.example.biletflow.data.repositories.*
import com.example.biletflow.domain.models.*
import com.example.biletflow.domain.session.*
import com.example.biletflow.presentation.auth.CurrentUserViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentUserTest {
    private class Storage : SessionStorage {
        var record: String? = null
        override suspend fun read() = record
        override suspend fun write(value: String) { record=value }
        override suspend fun clear() { record=null }
    }
    private fun requests(client: HttpClient, store: SessionStore, scope: CoroutineScope) =
        SessionOrdersRepository(OrdersRepositoryImpl(client,"http://test"),AuthRepositoryImpl(client,"http://test"),store,scope)

    @Test fun loadsAfterLoginAndRestorationAndPreservesGenerationOnRotation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val storage=Storage()
        val store=SessionStore(storage,this)
        val vm=CurrentUserViewModel(client,requests(client,store,backgroundScope),store,"http://test")
        var restoredVm: CurrentUserViewModel?=null
        try {
            runCurrent()
            store.save(AuthRepositoryImpl(client,"http://test").login("visitor@biletflow.kz","password123"))
            vm.state.first { it.user != null }
            assertEquals(100,store.user.value?.id)
            assertTrue(requireNotNull(store.user.value).emailVerified)
            val generation=store.generation
            store.rotateTokens(requireNotNull(store.tokens.value).copy(accessToken="expired"))
            vm.load()
            vm.state.first { !it.loading && it.user != null }
            assertEquals(generation,store.generation)
            assertNotEquals("expired",store.tokens.value?.accessToken)
            advanceUntilIdle()
            val restored=SessionStore(storage,this)
            restored.restore()
            restoredVm=CurrentUserViewModel(client,requests(client,restored,backgroundScope),restored,"http://test")
            restoredVm.state.first { it.user != null }
            assertEquals(store.user.value,restored.user.value)
        } finally { vm.viewModelScope.cancel(); restoredVm?.viewModelScope?.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun networkFailureKeepsTokensAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val auth=AuthRepositoryImpl(client,"http://test")
        auth.register("me-offline@biletflow.kz","password123")
        val store=SessionStore().also { it.save(auth.login("me-offline@biletflow.kz","password123")) }
        val vm=CurrentUserViewModel(client,requests(client,store,backgroundScope),store,"http://test")
        try {
            vm.state.first { it.error != null }
            assertNotNull(store.tokens.value)
            vm.load()
            vm.state.first { it.user != null }
            assertEquals("me-offline@biletflow.kz",store.user.value?.email)
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun suspensionFromMeOrAnotherProtectedEndpointClearsPersistentRecord() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for(email in listOf("suspend-on-me@biletflow.kz","suspend-on-orders@biletflow.kz")) {
                val client=createMockEventsHttpClient()
                val storage=Storage()
                val store=SessionStore(storage,this)
                val auth=AuthRepositoryImpl(client,"http://test")
                auth.register(email,"password123")
                store.save(auth.login(email,"password123"))
                val repo=requests(client,store,backgroundScope)
                val vm=CurrentUserViewModel(client,repo,store,"http://test")
                try {
                    if(email.contains("orders")) {
                        vm.state.first { it.user != null }
                        assertFailsWith<OrdersApiException> { repo.getMyOrders(requireNotNull(store.tokens.value).accessToken) }
                    }
                    store.suspended.first { it }
                    advanceUntilIdle()
                    assertNull(store.tokens.value)
                    assertNull(store.user.value)
                    assertNull(storage.record)
                } finally { vm.viewModelScope.cancel(); client.close() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lateSuspensionCannotClearNewAccountAndOtherForbiddenDoesNotLogOut() = runTest {
        val store=SessionStore().also { it.save(AuthTokens("old","r","bearer")) }
        val client=createMockEventsHttpClient()
        try {
            val repo=requests(client,store,backgroundScope)
            val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val old=async { runCatching { repo.request<Unit> {
                started.complete(Unit); release.await()
                throw OrdersApiException(403,"UserSuspendedError","Suspended")
            } } }
            started.await()
            store.clear(); store.save(AuthTokens("new","r2","bearer"))
            release.complete(Unit); old.await()
            assertEquals("new",store.tokens.value?.accessToken)
            assertFalse(store.suspended.value)
            assertFailsWith<OrdersApiException> { repo.request<Unit> { throw OrdersApiException(403,"NotEventStaffError","Denied") } }
            assertEquals("new",store.tokens.value?.accessToken)
            val error=assertFailsWith<AuthApiException> { AuthRepositoryImpl(client,"http://test").login("suspended@biletflow.kz","password123") }
            assertEquals("UserSuspendedError",error.errorCode)
            assertEquals(401,assertFailsWith<AuthApiException> {
                AuthRepositoryImpl(client,"http://test").login("suspended@biletflow.kz","wrong-password")
            }.statusCode)
        } finally { client.close() }
    }

    @Test fun serverRolesCanBeCombinedAndUseOrganizerProfileId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=HttpClient(MockEngine {
            respond("""{"id":100,"email":"owner@example.com","email_verified":true,"is_platform_admin":true,"is_event_staff":true,"organizer":{"id":31,"user_id":100,"status":"approved"}}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        val store=SessionStore().also { it.save(AuthTokens("a","r","bearer")) }
        val vm=CurrentUserViewModel(client,requests(client,store,backgroundScope),store,"http://test")
        try {
            val user=requireNotNull(vm.state.first { it.user != null }.user)
            assertTrue(user.canScan); assertTrue(user.isPlatformAdmin); assertTrue(user.isEventStaff)
            assertEquals(31,user.organizer?.id); assertEquals(100,user.id)
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun mockApplicationRefreshesProfileAndStaffDoesNotRequireOrganizerRole() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client=createMockEventsHttpClient()
        val auth=AuthRepositoryImpl(client,"http://test")
        val store=SessionStore().also { it.save(auth.login("visitor@biletflow.kz","password123")) }
        val repo=requests(client,store,backgroundScope)
        val vm=CurrentUserViewModel(client,repo,store,"http://test")
        val organizer=com.example.biletflow.presentation.organizer.OrganizerViewModel(client,repo,store,"http://test",vm::refreshAfterChange)
        try {
            vm.state.first { it.user != null }
            assertNull(store.user.value?.organizer)
            organizer.openForm(); organizer.apply()
            vm.state.first { it.user?.organizer?.status == "pending" }
            repeat(2) { vm.load(); vm.state.first { !it.loading } }
            assertEquals("approved",store.user.value?.organizer?.status)
            val result=repo.request { token -> client.post("http://test/events/102/staff") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"email":"empty@biletflow.kz"}""")
            } }
            assertTrue(result.status.value in 200..299)
            store.save(auth.login("empty@biletflow.kz","password123"))
            vm.state.first { it.user?.email == "empty@biletflow.kz" }
            assertTrue(requireNotNull(store.user.value).isEventStaff)
            assertNull(store.user.value?.organizer)
            assertTrue(requireNotNull(store.user.value).canScan)
        } finally { organizer.viewModelScope.cancel(); vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }
}
