package com.fajarnuha.kson.playground

import com.fajarnuha.kson.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UserResponseDslTest {
    @Test
    fun buildsTheReadmeExample() {
        val user: UserResponse = userResponseKson {
            id = 1
            name = "Sample"
            username = "sample"
            email = "sample@example.com"
            address {
                city = "Example City"
                geo { lat = "-1.23"; lng = "4.56" }
            }
        }
        val copy = userResponseKson {
            id = 2
            name = "Other"
            username = "other"
            email = "other@example.com"
            address = user.address
        }
        assertEquals(
            Json.parse(
                """{"id":1,"name":"Sample","username":"sample","email":"sample@example.com",
                   "address":{"city":"Example City","geo":{"lat":"-1.23","lng":"4.56"}}}""",
            ),
            UserResponseJson.encode(user),
        )
        assertEquals(user.address, copy.address)
    }
}
