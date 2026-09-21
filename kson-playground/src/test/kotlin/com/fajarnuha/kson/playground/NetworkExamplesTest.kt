package com.fajarnuha.kson.playground

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkExamplesTest {
    @Test
    fun bothClientsReceiveJsonAsKsonValues() = runBlocking {
        val json = """{"title":"Sample","items":[true,null,12345678901234567890]}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/data") { exchange ->
            val body = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/data"
            assertEquals(json, fetchWithKtor(url).toJson())
            assertEquals(json, fetchWithRetrofit(url).toJson())
        } finally {
            server.stop(0)
        }
    }
}
