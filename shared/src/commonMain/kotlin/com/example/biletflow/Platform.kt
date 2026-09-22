package com.example.biletflow

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform