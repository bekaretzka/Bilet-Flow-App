package com.example.biletflow

import com.example.biletflow.domain.models.AuthTokens
import com.example.biletflow.domain.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStorageTest {
    private class Storage : SessionStorage {
        var record: String? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read() = record
        override suspend fun write(value: String) { gate?.await(); record = value }
        override suspend fun clear() { record = null }
    }
    @Test fun restoresAndPersistsRotationAndClear() = runTest {
        val storage = Storage()
        val store = SessionStore(storage, this)
        store.restore()
        val first = AuthTokens("a", "r", "bearer")
        store.save(first)
        advanceUntilIdle()
        val restored = SessionStore(storage, this)
        restored.restore()
        assertEquals(first, restored.tokens.value)
        val generation = restored.generation
        val rotated = AuthTokens("b", "s", "bearer")
        restored.rotateTokens(rotated)
        advanceUntilIdle()
        assertEquals(generation, restored.generation)
        val restarted = SessionStore(storage, this)
        restarted.restore()
        assertEquals(rotated, restarted.tokens.value)
        restarted.clear()
        advanceUntilIdle()
        assertNull(storage.record)
        val guest = SessionStore(storage, this)
        guest.restore()
        assertNull(guest.tokens.value)
    }
    @Test fun logoutWinsAgainstInFlightWrite() = runTest {
        val storage = Storage().apply { gate = CompletableDeferred() }
        val store = SessionStore(storage, this)
        store.save(AuthTokens("a", "r", "bearer"))
        runCurrent()
        store.clear()
        storage.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(storage.record)
        assertNull(store.tokens.value)
    }
    @Test fun corruptRecordIsRemoved() = runTest {
        val storage = Storage().apply { record = "broken" }
        val store = SessionStore(storage, this)
        store.restore()
        assertTrue(store.storageError.value)
        assertNull(store.tokens.value)
        assertNull(storage.record)
    }
}
