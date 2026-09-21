package com.fajarnuha.kson.playground

import com.fajarnuha.kson.Kson

@Kson
interface UserResponse {
    val id: Long
    val name: String
    val username: String
    val email: String
    val address: Address

    interface Address {
        val city: String
        val geo: Geo

        interface Geo {
            val lat: String
            val lng: String
        }
    }
}
