package bench.moshi

import com.squareup.moshi.JsonClass

enum class Role { ADMIN, EDITOR, VIEWER }

@JsonClass(generateAdapter = true)
data class UserPage(val page: Int, val total: Int, val users: List<User>)

@JsonClass(generateAdapter = true)
data class User(
    val id: Long,
    val name: String,
    val username: String,
    val email: String,
    val active: Boolean,
    val score: Double,
    val role: Role,
    val nickname: String? = null,
    val bio: String,
    val address: Address,
    val tags: List<String>,
    val friends: List<Friend>,
)

@JsonClass(generateAdapter = true)
data class Address(val street: String, val city: String, val zipcode: String, val geo: Geo)

@JsonClass(generateAdapter = true)
data class Geo(val lat: Double, val lng: Double)

@JsonClass(generateAdapter = true)
data class Friend(val id: Long, val name: String)
