package com.example.biletflow.domain.session

interface SessionStorage {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun clear()
}
