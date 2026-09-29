package bench.kson

import com.fajarnuha.kson.Kson

enum class Role { ADMIN, EDITOR, VIEWER }

@Kson
interface UserPage {
    val page: Int
    val total: Int
    val users: List<User>
}

@Kson
interface User {
    val id: Long
    val name: String
    val username: String
    val email: String
    val active: Boolean
    val score: Double
    val role: Role
    val nickname: String?
    val bio: String
    val address: Address
    val tags: List<String>
    val friends: List<Friend>

    interface Address {
        val street: String
        val city: String
        val zipcode: String
        val geo: Geo

        interface Geo {
            val lat: Double
            val lng: Double
        }
    }

    interface Friend {
        val id: Long
        val name: String
    }
}
