package com.example.biletflow.domain.repositories

import com.example.biletflow.domain.models.AuthTokens

interface AuthRepository {
    suspend fun refresh(refreshToken: String): AuthTokens = error("Refresh not configured")
    suspend fun logout(refreshToken: String) { }
    suspend fun login(email: String, password: String): AuthTokens
    suspend fun register(email: String, password: String)
    /** POST /auth/forgot-password: the answer never reveals whether the account exists. */
    suspend fun forgotPassword(email: String): String = error("Forgot password not configured")
}
