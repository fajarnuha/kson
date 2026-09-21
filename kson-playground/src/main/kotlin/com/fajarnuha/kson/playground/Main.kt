package com.fajarnuha.kson.playground

import com.fajarnuha.kson.JsonFormat
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.get
import com.fajarnuha.kson.json
import com.fajarnuha.kson.stringOrNull

suspend fun main(args: Array<String>) {
    val url = args.getOrNull(1) ?: "https://jsonplaceholder.typicode.com/todos/1"
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

private fun show(client: String, value: JsonValue) {
    println("$client title: ${value["title"]?.stringOrNull}")
    println(value.toJson(JsonFormat.Pretty))

}


val example = json {
    "name" to "Fajar"
    "height" to 180
}

data class ExampleKson(
    private val name: String,

)
