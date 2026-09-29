package com.fajarnuha.kson

@DslMarker
public annotation class JsonDsl

/**
 * Builder receiver for [json]. Inside the block, `"key" to value` puts a field (shadowing `kotlin.to`),
 * `"key" to { ... }` and `"key" { ... }` nest objects, and [arr] builds arrays.
 */
@JsonDsl
public class JsonObjectBuilder internal constructor(initial: Map<String, JsonValue> = emptyMap()) {
    private val fields = LinkedHashMap<String, JsonValue>(initial)

    /** `"key" to value` — puts any Kotlin value (see [toJsonValue]). Shadows `kotlin.to` inside the block. */
    public infix fun String.to(value: Any?) {
        fields[this] = value.toJsonValue()
    }

    /** `"key" to { ... }` — nested object without naming a builder function. */
    public infix fun String.to(block: JsonObjectBuilder.() -> Unit) {
        fields[this] = obj(block)
    }

    /** `"key" { ... }` — even shorter nested-object form. */
    public operator fun String.invoke(block: JsonObjectBuilder.() -> Unit) {
        fields[this] = obj(block)
    }

    /** Puts a field. Equivalent to `key to value`. */
    public fun put(key: String, value: Any?) {
        fields[key] = value.toJsonValue()
    }

    /** Puts every entry of [map]. Keys are stringified. */
    public fun putAll(map: Map<*, *>) {
        for ((k, v) in map) fields[k.toString()] = v.toJsonValue()
    }

    /** Puts a field only when [value] is not `null`, otherwise leaves the object untouched. */
    public fun putIfNotNull(key: String, value: Any?) {
        if (value != null) fields[key] = value.toJsonValue()
    }

    /** Removes a field previously added in this builder. */
    public fun remove(key: String): JsonValue? = fields.remove(key)

    /** Builds a nested object value, e.g. `"address" to obj { ... }`. */
    public fun obj(block: JsonObjectBuilder.() -> Unit): JsonObject = JsonObjectBuilder().apply(block).build()

    /** Builds a flat array value from [items], e.g. `"tags" to arr("a", "b")`. */
    public fun arr(vararg items: Any?): JsonArray = JsonArray(items.map { it.toJsonValue() })

    /** Builds an array value with a block, e.g. `"devices" to arr { obj { ... }; +"raw" }`. */
    public fun arr(block: JsonArrayBuilder.() -> Unit): JsonArray = JsonArrayBuilder().apply(block).build()

    public fun build(): JsonObject = JsonObject(fields.toMap(LinkedHashMap()))
}

/**
 * Builder receiver for [jsonArray]. Use [add] or unary `+` to append; [obj] and [arr] append nested containers.
 */
@JsonDsl
public class JsonArrayBuilder internal constructor(initial: List<JsonValue> = emptyList()) {
    private val items = ArrayList<JsonValue>(initial)

    /** Appends any Kotlin value (see [toJsonValue]). */
    public fun add(value: Any?) {
        items.add(value.toJsonValue())
    }

    /** Appends every element of [values]. */
    public fun addAll(values: Iterable<Any?>) {
        for (v in values) items.add(v.toJsonValue())
    }

    /**
     * `+value` appends — but NOT for numeric literals: `+1` resolves to `Int.unaryPlus`. Use `add(1)` for numbers.
     */
    public operator fun Any?.unaryPlus() {
        add(this)
    }

    @Deprecated(
        "obj { } and arr { } already append themselves inside an array builder; drop the leading '+'.",
        level = DeprecationLevel.ERROR,
    )
    public operator fun Unit.unaryPlus() {
    }

    /** Builds and appends a nested object. */
    public fun obj(block: JsonObjectBuilder.() -> Unit) {
        items.add(JsonObjectBuilder().apply(block).build())
    }

    /** Builds and appends a nested array. */
    public fun arr(block: JsonArrayBuilder.() -> Unit) {
        items.add(JsonArrayBuilder().apply(block).build())
    }

    /** Appends a nested array built from [values]. */
    public fun arr(vararg values: Any?) {
        items.add(JsonArray(values.map { it.toJsonValue() }))
    }

    public fun build(): JsonArray = JsonArray(items.toList())
}

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

/** Builds a [JsonObject]: `json { "id" to 1; "tags" to arr("a", "b"); "geo" { "lat" to 1.0 } }`. */
public fun json(block: JsonObjectBuilder.() -> Unit): JsonObject = JsonObjectBuilder().apply(block).build()

/** Builds a [JsonArray] with a block: `jsonArray { add(1); +"two"; obj { "three" to 3 } }`. */
public fun jsonArray(block: JsonArrayBuilder.() -> Unit): JsonArray = JsonArrayBuilder().apply(block).build()

/** Builds a flat [JsonArray] from [items]. */
public fun jsonArray(vararg items: Any?): JsonArray = JsonArray(items.map { it.toJsonValue() })

/** Builds a [JsonObject] from pairs: `jsonObjectOf("a" to 1, "b" to null)`. */
public fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associateTo(LinkedHashMap()) { (k, v) -> k to v.toJsonValue() })

/** Builds a flat [JsonArray] from [items]. Alias of the vararg [jsonArray]. */
public fun jsonArrayOf(vararg items: Any?): JsonArray = JsonArray(items.map { it.toJsonValue() })

/** Returns a copy of this object with the changes made in [block] applied on top of the existing fields. */
public fun JsonObject.buildUpon(block: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObjectBuilder(fields).apply(block).build()

/** Returns a copy of this array with the changes made in [block] appended to the existing items. */
public fun JsonArray.buildUpon(block: JsonArrayBuilder.() -> Unit): JsonArray =
    JsonArrayBuilder(items).apply(block).build()
