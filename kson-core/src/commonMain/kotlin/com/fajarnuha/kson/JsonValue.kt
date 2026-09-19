package com.fajarnuha.kson

/**
 * Immutable JSON value model.
 *
 * Every value renders itself back to JSON text via [toJson]; containers also expose the
 * regular Kotlin collection API ([JsonObject] is a `Map`, [JsonArray] is a `List`).
 */
public sealed interface JsonValue {
    /** Serialises this value. [pretty] uses two-space indentation; see [JsonFormat] for full control. */
    public fun toJson(pretty: Boolean = false): String =
        JsonWriter.write(this, if (pretty) JsonFormat.Pretty else JsonFormat.Compact)

    /** Serialises this value using an explicit [JsonFormat]. */
    public fun toJson(format: JsonFormat): String = JsonWriter.write(this, format)
}

/** A JSON string. */
public data class JsonString(public val value: String) : JsonValue {
    override fun toString(): String = toJson()
}

/** A JSON boolean. */
public data class JsonBool(public val value: Boolean) : JsonValue {
    override fun toString(): String = value.toString()

    public companion object {
        public val True: JsonBool = JsonBool(true)
        public val False: JsonBool = JsonBool(false)

        public fun of(value: Boolean): JsonBool = if (value) True else False
    }
}

/** The JSON `null` literal. */
public data object JsonNull : JsonValue {
    override fun toString(): String = "null"
}

/** A JSON array. Behaves as an immutable `List<JsonValue>`. */
public data class JsonArray(public val items: List<JsonValue>) : JsonValue, List<JsonValue> by items {
    override fun toString(): String = toJson()

    public companion object {
        public val Empty: JsonArray = JsonArray(emptyList())
    }
}

/** A JSON object. Behaves as an immutable `Map<String, JsonValue>` that preserves insertion order. */
public data class JsonObject(public val fields: Map<String, JsonValue>) : JsonValue, Map<String, JsonValue> by fields {
    override fun toString(): String = toJson()

    public companion object {
        public val Empty: JsonObject = JsonObject(emptyMap())
    }
}

/** Base type of every exception thrown by kson. */
public open class JsonException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when text is not valid JSON. [line] and [column] are 1-based; [offset] is the 0-based char index.
 * [description] is the problem without position information; [message] includes the position.
 */
public class JsonParseException(
    public val description: String,
    public val line: Int,
    public val column: Int,
    public val offset: Int,
) : JsonException("$description at line $line, column $column")

/** Thrown when a value is accessed as a type it is not (see [string], [int], [jsonObject], ...). */
public class JsonTypeException(message: String) : JsonException(message)
