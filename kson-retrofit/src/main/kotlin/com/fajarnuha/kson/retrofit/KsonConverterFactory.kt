package com.fajarnuha.kson.retrofit

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.ksonReaderOf
import com.fajarnuha.kson.ksonWriterOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Converts request and response bodies to and from [com.fajarnuha.kson.Kson] interfaces, KSON values,
 * and lists of either. Other types are left to the next converter factory.
 */
public class KsonConverterFactory private constructor() : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        val reader = ksonReaderOf(type) ?: return null
        return Converter<ResponseBody, Any?> { body -> body.use { reader(Json.parse(it.string())) } }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? {
        val writer = ksonWriterOf(type) ?: return null
        return Converter<Any?, RequestBody> { value -> writer(value).toJson().toRequestBody(jsonMediaType) }
    }

    public companion object {
        private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

        @JvmStatic
        public fun create(): KsonConverterFactory = KsonConverterFactory()
    }
}
