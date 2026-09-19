package com.fajarnuha.kson

// ---------------------------------------------------------------------------
// Typed accessors. The `...OrNull` variants return null on a type mismatch; the
// plain variants throw JsonTypeException.
// ---------------------------------------------------------------------------

private fun JsonValue.typeName(): String = when (this) {
    JsonNull -> "null"
    is JsonBool -> "boolean"
    is JsonNumber -> "number"
    is JsonString -> "string"
    is JsonArray -> "array"
    is JsonObject -> "object"
}

/** The JSON type name of this value: `null`, `boolean`, `number`, `string`, `array` or `object`. */
public val JsonValue.jsonType: String get() = typeName()

private fun JsonValue.mismatch(expected: String): Nothing =
    throw JsonTypeException("Expected $expected but was ${typeName()}: ${toJson().take(80)}")

public val JsonValue.isNull: Boolean get() = this === JsonNull

public val JsonValue.jsonObjectOrNull: JsonObject? get() = this as? JsonObject
public val JsonValue.jsonObject: JsonObject get() = this as? JsonObject ?: mismatch("object")

public val JsonValue.jsonArrayOrNull: JsonArray? get() = this as? JsonArray
public val JsonValue.jsonArray: JsonArray get() = this as? JsonArray ?: mismatch("array")

public val JsonValue.stringOrNull: String? get() = (this as? JsonString)?.value
public val JsonValue.string: String get() = stringOrNull ?: mismatch("string")

public val JsonValue.booleanOrNull: Boolean? get() = (this as? JsonBool)?.value
public val JsonValue.boolean: Boolean get() = booleanOrNull ?: mismatch("boolean")

public val JsonValue.numberOrNull: JsonNumber? get() = this as? JsonNumber
public val JsonValue.number: JsonNumber get() = numberOrNull ?: mismatch("number")

/** The value as an [Int], or `null` when it is not an integral number that fits in an Int. */
public val JsonValue.intOrNull: Int? get() = (this as? JsonNumber)?.toIntOrNull()
public val JsonValue.int: Int get() = intOrNull ?: mismatch("integer")

/** The value as a [Long], or `null` when it is not an integral number that fits in a Long. */
public val JsonValue.longOrNull: Long? get() = (this as? JsonNumber)?.toLongOrNull()
public val JsonValue.long: Long get() = longOrNull ?: mismatch("integer")

public val JsonValue.doubleOrNull: Double? get() = (this as? JsonNumber)?.toDouble()
public val JsonValue.double: Double get() = doubleOrNull ?: mismatch("number")

public val JsonValue.floatOrNull: Float? get() = (this as? JsonNumber)?.toFloat()
public val JsonValue.float: Float get() = floatOrNull ?: mismatch("number")

// ---------------------------------------------------------------------------
// Navigation. `value["key"]` and `value[index]` return null instead of throwing
// when the receiver is not a container or the member is missing.
// ---------------------------------------------------------------------------

/**
 * Field lookup that returns `null` when the receiver is `null`, not an object, or lacks [key].
 * Works on nullable values so lookups chain: `root["a"]["b"][0]`.
 */
public operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonObject)?.fields?.get(key)

/**
 * Index lookup that returns `null` when the receiver is `null`, not an array, or [index] is out of range.
 * Note that on a value statically typed as [JsonArray] the `List.get` member wins and throws when out of range.
 */
public operator fun JsonValue?.get(index: Int): JsonValue? = (this as? JsonArray)?.items?.getOrNull(index)

/** Field lookup that throws [JsonTypeException] when the key is absent. */
public fun JsonObject.require(key: String): JsonValue =
    fields[key] ?: throw JsonTypeException("Missing required key \"$key\" in object ${toJson().take(80)}")

/** Whether this object contains [key] and its value is not JSON `null`. */
public fun JsonObject.hasNonNull(key: String): Boolean = fields[key].let { it != null && it !== JsonNull }
