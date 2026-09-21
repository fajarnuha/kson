package com.fajarnuha.kson.playground

import com.fajarnuha.kson.string
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkExamplesTest {
    @Test
    fun bothClientsReceiveTypedKsonValues() = runBlocking {
        val json = """{"id":1,"name":"Sample","username":"sample","email":"sample@example.com","address":{"city":"Example City","geo":{"lat":"-1.23","lng":"4.56"}},"ignored":true}"""
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
            val ktor = fetchWithKtor(url)
            val retrofit = fetchWithRetrofit(url)
            assertEquals("Sample", ktor.name)
            assertEquals("-1.23", ktor.address.geo.lat)
            assertEquals("4.56", retrofit.address.geo.lng)
            assertEquals("UserResponse", UserResponseJson.schema["title"]?.string)
        } finally {
            server.stop(0)
        }
    }
}
