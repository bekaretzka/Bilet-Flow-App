package com.example.biletflow.domain.session

import com.example.biletflow.domain.models.AuthTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore {
    private val _tokens = MutableStateFlow<AuthTokens?>(null)
    val tokens: StateFlow<AuthTokens?> = _tokens.asStateFlow()

    fun save(tokens: AuthTokens) {
        _tokens.value = tokens
    }

    fun clear() {
        _tokens.value = null
    }
}
