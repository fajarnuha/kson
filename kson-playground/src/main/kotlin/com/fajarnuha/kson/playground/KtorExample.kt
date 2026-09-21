package com.fajarnuha.kson.playground

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

suspend fun fetchWithKtor(url: String): JsonValue = HttpClient(CIO) {
    expectSuccess = true
}.use { client ->
    Json.parse(client.get(url).bodyAsText())
}
