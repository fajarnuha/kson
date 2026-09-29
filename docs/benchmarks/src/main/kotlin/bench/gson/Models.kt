package bench.gson

// Gson needs no annotations: it binds fields reflectively.

enum class Role { ADMIN, EDITOR, VIEWER }

data class UserPage(val page: Int, val total: Int, val users: List<User>)

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

data class Address(val street: String, val city: String, val zipcode: String, val geo: Geo)

data class Geo(val lat: Double, val lng: Double)

data class Friend(val id: Long, val name: String)
