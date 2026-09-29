package com.fajarnuha.kson

/**
 * Converts any Kotlin value into the [JsonValue] model so existing data structures drop straight in:
 *
 * - `null` → [JsonNull]
 * - [JsonValue] → itself
 * - [String], [CharSequence], [Char] → [JsonString]
 * - [Number], [UInt], [ULong], [UShort], [UByte] → [JsonNumber]
 * - [Boolean] → [JsonBool]
 * - [Enum] → [JsonString] of its `name`
 * - [Map] → [JsonObject] (keys are stringified)
 * - [Iterable], [Sequence], arrays and primitive arrays → [JsonArray]
 * - [Pair] → single-entry [JsonObject]
 * - anything else → [JsonString] of its `toString()`
 */
public fun Any?.toJsonValue(): JsonValue = when (this) {
    null -> JsonNull
    is JsonValue -> this
    is String -> JsonString(this)
    is Boolean -> JsonBool.of(this)
    is Number -> JsonNumber(this)
    is Char -> JsonString(toString())
    is CharSequence -> JsonString(toString())
    is UInt, is ULong, is UShort, is UByte -> JsonNumber.parse(toString())
    is Enum<*> -> JsonString(name)
    is Map<*, *> -> JsonObject(entries.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to v.toJsonValue() })
    is Iterable<*> -> JsonArray(map { it.toJsonValue() })
    is Sequence<*> -> JsonArray(map { it.toJsonValue() }.toList())
    is Array<*> -> JsonArray(map { it.toJsonValue() })
    is IntArray -> JsonArray(map { JsonNumber(it) })
    is LongArray -> JsonArray(map { JsonNumber(it) })
    is DoubleArray -> JsonArray(map { JsonNumber(it) })
    is FloatArray -> JsonArray(map { JsonNumber(it) })
    is ShortArray -> JsonArray(map { JsonNumber(it) })
    is ByteArray -> JsonArray(map { JsonNumber(it) })
    is BooleanArray -> JsonArray(map { JsonBool.of(it) })
    is CharArray -> JsonArray(map { JsonString(it.toString()) })
    is Pair<*, *> -> JsonObject(mapOf(first.toString() to second.toJsonValue()))
    else -> JsonString(toString())
}

/**
 * Converts the model back into plain Kotlin values:
 * `JsonNull` → `null`, `JsonString` → `String`, `JsonNumber` → `Long`/`Double`, `JsonBool` → `Boolean`,
 * `JsonArray` → `List<Any?>`, `JsonObject` → `Map<String, Any?>`.
 */
public fun JsonValue.toKotlin(): Any? = when (this) {
    JsonNull -> null
    is JsonString -> value
    is JsonNumber -> value
    is JsonBool -> value
    is JsonArray -> items.map { it.toKotlin() }
    is JsonObject -> fields.mapValuesTo(LinkedHashMap()) { (_, v) -> v.toKotlin() }
}
