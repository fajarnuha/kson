package com.fajarnuha.kson.ktor

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonException
import com.fajarnuha.kson.ksonReaderOf
import com.fajarnuha.kson.ksonWriterOf
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharsetIfNeeded
import io.ktor.serialization.Configuration
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.JsonConvertException
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.reifiedType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining

/**
 * Reads and writes bodies as [com.fajarnuha.kson.Kson] interfaces, KSON values, and lists of either.
 * Other types are left to the next registered converter.
 */
public class KsonConverter : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        if (value == null) return null
        val writer = ksonWriterOf(typeInfo.reifiedType) ?: ksonWriterOf(value.javaClass) ?: return null
        return TextContent(writer(value).toJson(), contentType.withCharsetIfNeeded(charset))
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        val reader = ksonReaderOf(typeInfo.reifiedType) ?: return null
        val text = content.readRemaining().readText(charset)
        val value = try {
            reader(Json.parse(text))
        } catch (error: JsonException) {
            throw JsonConvertException("Unable to read $typeInfo: ${error.message}", error)
        }
        if (value == null && !typeInfo.isNullable) {
            throw JsonConvertException("Unable to read $typeInfo: the body is JSON null")
        }
        return value
    }
}

/** Registers [KsonConverter] for [contentType] in a client or server `ContentNegotiation` plugin. */
public fun Configuration.kson(contentType: ContentType = ContentType.Application.Json) {
    register(contentType, KsonConverter())
}
