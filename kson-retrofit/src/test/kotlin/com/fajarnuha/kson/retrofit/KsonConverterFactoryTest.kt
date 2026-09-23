package com.fajarnuha.kson.retrofit

import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.Kson
import com.fajarnuha.kson.int
import com.fajarnuha.kson.json
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Kson
interface User {
    val id: Long
    val name: String
    val role: Role?

    enum class Role { ADMIN, MEMBER }
}

private data class NewUser(override val id: Long, override val name: String, override val role: User.Role?) : User

private interface Api {
    @GET("user")
    fun user(): Call<User>

    @GET("users")
    suspend fun users(): List<User>

    @GET("null")
    fun missing(): Call<User>

    @GET("user")
    suspend fun raw(): JsonObject

    @POST("echo")
    suspend fun echo(@Body body: JsonObject): JsonObject

    @POST("echo")
    suspend fun echoUser(@Body user: User): JsonObject

    @POST("echo")
    suspend fun echoUsers(@Body users: List<@JvmSuppressWildcards User>): List<User>

    @POST("echo")
    suspend fun echoNewUser(@Body user: NewUser): User
}

class KsonConverterFactoryTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private lateinit var api: Api

    @BeforeTest
    fun start() {
        val user = """{"id":1,"name":"Sample","role":"ADMIN","ignored":true}"""
        server.respond("/user") { user }
        server.respond("/users") { """[$user,{"id":2,"name":"Other","role":null}]""" }
        server.respond("/null") { "null" }
        server.respond("/echo") { it }
        server.start()
        api = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:${server.address.port}/")
            .addConverterFactory(KsonConverterFactory.create())
            .build()
            .create(Api::class.java)
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun decodesKsonInterfaces() {
        val user = api.user().execute().body()!!
        assertEquals(1, user.id)
        assertEquals("Sample", user.name)
        assertEquals(User.Role.ADMIN, user.role)
    }

    @Test
    fun decodesListsFromSuspendFunctions() = runBlocking {
        val users = api.users()
        assertEquals(listOf("Sample", "Other"), users.map { it.name })
        assertNull(users[1].role)
    }

    @Test
    fun decodesJsonNullAsNullBody() {
        assertNull(api.missing().execute().body())
    }

    @Test
    fun readsAndWritesKsonValues() = runBlocking {
        assertEquals(1, api.raw()["id"]?.int)
        val body = json { "name" to "Sample"; "tags" to arr("a", "b") }
        assertEquals(body, api.echo(body))
    }

    @Test
    fun writesKsonInterfaces() = runBlocking {
        val user = NewUser(3, "New", User.Role.MEMBER)
        assertEquals(json { "id" to 3; "name" to "New"; "role" to "MEMBER" }, api.echoUser(user))
        assertEquals(user.name, api.echoNewUser(user).name)
        val users = api.echoUsers(listOf(user, NewUser(4, "Other", null)))
        assertEquals(listOf(3L, 4L), users.map { it.id })
        assertNull(users[1].role)
    }

    @Test
    fun leavesOtherTypesToOtherFactories() {
        val retrofit = Retrofit.Builder().baseUrl("http://127.0.0.1/").build()
        val factory = KsonConverterFactory.create()
        assertNull(factory.responseBodyConverter(String::class.java, emptyArray(), retrofit))
        assertNull(factory.requestBodyConverter(String::class.java, emptyArray(), emptyArray(), retrofit))
    }
}

private fun HttpServer.respond(path: String, body: (request: String) -> String) {
    createContext(path) { exchange ->
        val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
        val bytes = body(request).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
