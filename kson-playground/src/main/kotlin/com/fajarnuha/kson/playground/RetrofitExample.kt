package com.fajarnuha.kson.playground

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonValue
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.lang.reflect.Type

private interface JsonApi {
    @GET
    fun fetch(@Url url: String): Call<JsonValue>
}

private class KsonConverterFactory : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? = if (type == JsonValue::class.java) {
        Converter<ResponseBody, JsonValue> { body -> body.use { Json.parse(it.string()) } }
    } else null
}

fun fetchWithRetrofit(url: String): JsonValue {
    val api = Retrofit.Builder()
        .baseUrl("https://jsonplaceholder.typicode.com/")
        .addConverterFactory(KsonConverterFactory())
        .build()
        .create(JsonApi::class.java)
    val response = api.fetch(url).execute()
    check(response.isSuccessful) { "HTTP ${response.code()}: ${response.message()}" }
    return requireNotNull(response.body()) { "The response body was empty" }
}
