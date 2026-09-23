package com.fajarnuha.kson.playground

import com.fajarnuha.kson.retrofit.KsonConverterFactory
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

private interface JsonApi {
    @GET
    fun fetch(@Url url: String): Call<UserResponse>
}

fun fetchWithRetrofit(url: String): UserResponse {
    val api = Retrofit.Builder()
        .baseUrl("https://jsonplaceholder.typicode.com/")
        .addConverterFactory(KsonConverterFactory.create())
        .build()
        .create(JsonApi::class.java)
    val response = api.fetch(url).execute()
    check(response.isSuccessful) { "HTTP ${response.code()}: ${response.message()}" }
    return requireNotNull(response.body()) { "The response body was empty" }
}
