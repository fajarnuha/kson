package com.fajarnuha.kson

// ---------------------------------------------------------------------------
// Functional updates on the immutable model.
// ---------------------------------------------------------------------------

/** Returns a copy with [other]'s fields put on top of this object's (shallow). */
public operator fun JsonObject.plus(other: JsonObject): JsonObject =
    JsonObject(LinkedHashMap(fields).apply { putAll(other.fields) })

/** Returns a copy with an extra (or replaced) field: `obj + ("k" to 1)`. */
public operator fun JsonObject.plus(pair: Pair<String, Any?>): JsonObject =
    JsonObject(LinkedHashMap(fields).apply { put(pair.first, pair.second.toJsonValue()) })

/** Returns a copy without [key]. */
public operator fun JsonObject.minus(key: String): JsonObject =
    if (key !in fields) this else JsonObject(LinkedHashMap(fields).apply { remove(key) })

/** Returns a copy with [key] set to [value] (converted via [toJsonValue]). */
public fun JsonObject.with(key: String, value: Any?): JsonObject = this + (key to value)

/**
 * Recursively merges [other] into this object: nested objects are merged key by key, every other
 * value (including arrays) in [other] replaces the value in this object.
 */
public fun JsonObject.deepMerge(other: JsonObject): JsonObject {
    val result = LinkedHashMap(fields)
    for ((k, v) in other.fields) {
        val existing = result[k]
        result[k] = if (existing is JsonObject && v is JsonObject) existing.deepMerge(v) else v
    }
    return JsonObject(result)
}

/** Returns a copy with [other]'s items appended. */
public operator fun JsonArray.plus(other: JsonArray): JsonArray = JsonArray(items + other.items)

/** Returns a copy with [item] appended (converted via [toJsonValue]). */
public operator fun JsonArray.plus(item: Any?): JsonArray = JsonArray(items + item.toJsonValue())

/** Returns a copy whose object keys (at every nesting level) are sorted lexicographically. */
public fun JsonValue.sortedKeys(): JsonValue = when (this) {
    is JsonObject -> JsonObject(fields.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to it.value.sortedKeys() })
    is JsonArray -> JsonArray(items.map { it.sortedKeys() })
    else -> this
}

/**
 * Depth-first traversal. [visit] receives every value with its JSON Pointer, the root first with `""`.
 */
public fun JsonValue.walk(visit: (pointer: String, value: JsonValue) -> Unit) {
    fun go(prefix: String, v: JsonValue) {
        visit(prefix, v)
        when (v) {
            is JsonObject -> for ((k, x) in v.fields) go(prefix + "/" + JsonPointer.escape(k), x)
            is JsonArray -> v.items.forEachIndexed { i, x -> go("$prefix/$i", x) }
            else -> {}
        }
    }
    go("", this)
}

/** Recursively drops fields whose value is JSON `null`. Arrays keep their `null` items (removing them would shift indices). */
public fun JsonValue.withoutNulls(): JsonValue = when (this) {
    is JsonObject -> JsonObject(fields.filterValues { it !== JsonNull }.mapValuesTo(LinkedHashMap()) { (_, v) -> v.withoutNulls() })
    is JsonArray -> JsonArray(items.map { it.withoutNulls() })
    else -> this
}
