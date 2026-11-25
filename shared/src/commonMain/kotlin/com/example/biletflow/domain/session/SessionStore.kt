package com.example.biletflow.domain.session

import com.example.biletflow.domain.models.AuthTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class SessionStore(
    private val storage: SessionStorage? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val mutex = Mutex()
    private var restoreStarted = false
    private val _restoring = MutableStateFlow(storage != null)
    val restoring = _restoring.asStateFlow()
    private val _storageError = MutableStateFlow(false)
    val storageError = _storageError.asStateFlow()
    private val _user = MutableStateFlow<com.example.biletflow.domain.models.CurrentUser?>(null)
    val user = _user.asStateFlow()
    private val _suspended = MutableStateFlow(false)
    val suspended = _suspended.asStateFlow()
    fun dismissSuspended() { _suspended.value = false }
    fun suspendSession(expected: Long) {
        if(generation != expected) return
        clear(); _suspended.value = true
    }
    fun setUser(user: com.example.biletflow.domain.models.CurrentUser, expected: Long) {
        if(generation != expected || _tokens.value == null) return
        _user.value = user
        _tokens.value = _tokens.value?.copy(email=user.email)
        persist()
    }
    fun dismissStorageError() { _storageError.value = false }

    suspend fun restore() {
        if (restoreStarted) return
        restoreStarted = true
        val startedGeneration = generation
        mutex.withLock {
            try {
                val record = storage?.read()
                val restored = record?.let { Json.decodeFromString<AuthTokens>(it) }
                require(restored == null || (restored.accessToken.isNotBlank() && restored.refreshToken.isNotBlank()))
                if (generation == startedGeneration && restored != null) {
                    generation++
                    _tokens.value = restored
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _storageError.value = true
                try { storage?.clear() } catch (_: Exception) { }
            } finally { _restoring.value = false }
        }
    }

    private fun persist() {
        if (storage == null) return
        val expected = generation
        val snapshot = _tokens.value
        scope.launch {
            mutex.withLock {
                if (expected != generation) return@withLock
                try {
                    if (snapshot == null) storage?.clear()
                    else storage?.write(Json.encodeToString(snapshot))
                    _storageError.value = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _storageError.value = true
                }
                // A clear queued during a write always runs after it under the same mutex.
            }
        }
    }
    // Changes on login/logout, but not when tokens rotate within the same session.
    var generation: Long = 0
        private set
    private val _tokens = MutableStateFlow<AuthTokens?>(null)
    val tokens: StateFlow<AuthTokens?> = _tokens.asStateFlow()

    fun save(tokens: AuthTokens) {
        _user.value = null
        _suspended.value = false
        generation++
        _tokens.value = tokens
        persist()
    }

    fun rotateTokens(tokens: AuthTokens) {
        _tokens.value = tokens.copy(email = _tokens.value?.email)
        persist()
    }

    fun clear() {
        _user.value = null
        generation++
        _tokens.value = null
        persist()
    }
}
