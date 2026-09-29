package bench.kotlinx

import kotlinx.serialization.Serializable

@Serializable
enum class Role { ADMIN, EDITOR, VIEWER }

@Serializable
data class UserPage(val page: Int, val total: Int, val users: List<User>)

@Serializable
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

@Serializable
data class Address(val street: String, val city: String, val zipcode: String, val geo: Geo)

@Serializable
data class Geo(val lat: Double, val lng: Double)

@Serializable
data class Friend(val id: Long, val name: String)
