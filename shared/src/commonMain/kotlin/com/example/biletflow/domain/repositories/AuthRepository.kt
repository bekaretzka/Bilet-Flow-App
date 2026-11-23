package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.AuthTokens

interface AuthRepository {
    suspend fun refresh(refreshToken: String): AuthTokens = error("Refresh not configured")
    suspend fun logout(refreshToken: String) { }
    suspend fun login(email: String, password: String): AuthTokens
    suspend fun register(email: String, password: String)
}
