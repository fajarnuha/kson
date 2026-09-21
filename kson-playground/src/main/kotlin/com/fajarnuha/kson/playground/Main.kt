package com.fajarnuha.kson.playground

import com.fajarnuha.kson.JsonFormat

suspend fun main(args: Array<String>) {
    val url = args.getOrNull(1) ?: "https://jsonplaceholder.typicode.com/users/1"
    when (args.firstOrNull()) {
        null -> {
            show("Ktor", fetchWithKtor(url))
            show("Retrofit", fetchWithRetrofit(url))
        }
        "ktor" -> show("Ktor", fetchWithKtor(url))
        "retrofit" -> show("Retrofit", fetchWithRetrofit(url))
        else -> error("Usage: ./gradlew :kson-playground:run --args='[ktor|retrofit] [url]'")
    }
}

private fun show(client: String, value: UserResponse) {
    println("$client: ${value.name} @ ${value.address.city}")
    println("Coordinates: ${value.address.geo.lat}, ${value.address.geo.lng}")
    println(UserResponseJson.schema.toJson(JsonFormat.Pretty))
}
