package com.example.biletflow.core.network

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
