package com.fajarnuha.kson.ktor

import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.Kson
import com.fajarnuha.kson.int
import com.fajarnuha.kson.json
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Kson
interface User {
    val id: Long
    val name: String
    val role: Role?

    enum class Role { ADMIN, MEMBER }
}

private data class NewUser(override val id: Long, override val name: String, override val role: User.Role?) : User

class KsonConverterTest {
    private val user = """{"id":1,"name":"Sample","role":"ADMIN","ignored":true}"""

    private val client = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val body = when (request.url.encodedPath) {
                    "/user" -> user
                    "/users" -> """[$user,{"id":2,"name":"Other","role":null}]"""
                    "/null" -> "null"
                    "/broken" -> """{"id":"one"}"""
                    "/echo" -> request.body.toByteArray().decodeToString()
                    else -> error("Unexpected ${request.url}")
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        install(ContentNegotiation) { kson() }
    }

    @Test
    fun decodesKsonInterfaces() = runBlocking {
        val user = client.get("/user").body<User>()
        assertEquals(1, user.id)
        assertEquals("Sample", user.name)
        assertEquals(User.Role.ADMIN, user.role)
    }

    @Test
    fun decodesLists() = runBlocking {
        val users = client.get("/users").body<List<User>>()
        assertEquals(listOf("Sample", "Other"), users.map { it.name })
        assertNull(users[1].role)
    }

    @Test
    fun decodesJsonNullOnlyForNullableTypes() = runBlocking {
        assertNull(client.get("/null").body<User?>())
        assertFailsWith<JsonConvertException> { client.get("/null").body<User>() }
        Unit
    }

    @Test
    fun wrapsDecodeErrors() = runBlocking {
        assertFailsWith<JsonConvertException> { client.get("/broken").body<User>() }
        Unit
    }

    @Test
    fun readsAndWritesKsonValues() = runBlocking {
        assertEquals(1, client.get("/user").body<JsonObject>()["id"]?.int)
        val body = json { "name" to "Sample"; "tags" to arr("a", "b") }
        val echoed = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<JsonObject>()
        assertEquals(body, echoed)
    }

    @Test
    fun writesKsonInterfaces() = runBlocking {
        val user: User = NewUser(3, "New", User.Role.MEMBER)
        val echoed = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody(user)
        }.body<JsonObject>()
        assertEquals(json { "id" to 3; "name" to "New"; "role" to "MEMBER" }, echoed)

        val users = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody(listOf(NewUser(3, "New", null), NewUser(4, "Other", User.Role.ADMIN)))
        }.body<List<User>>()
        assertEquals(listOf(3L, 4L), users.map { it.id })
        assertEquals(User.Role.ADMIN, users[1].role)
    }
}
