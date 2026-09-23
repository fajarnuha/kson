package com.fajarnuha.kson.playground

import com.fajarnuha.kson.ktor.kson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get

suspend fun fetchWithKtor(url: String): UserResponse = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) { kson() }
}.use { client ->
    client.get(url).body()
}
