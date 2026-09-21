package com.fajarnuha.kson.playground

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

suspend fun fetchWithKtor(url: String): UserResponse = HttpClient(CIO) {
    expectSuccess = true
}.use { client ->
    UserResponseJson.decode(client.get(url).bodyAsText())
}
