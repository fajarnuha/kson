package com.fajarnuha.kson

/**
 * Options for [toJsonSchema].
 *
 * @property title optional `title` for the root schema.
 * @property description optional `description` for the root schema.
 * @property schemaUri value of `$schema`; `null` omits the keyword.
 * @property requireAllProperties list every observed property in `required`. When arrays of objects are
 * merged, only properties present in every element stay required.
 * @property additionalProperties value to emit for `additionalProperties` on every object; `null` omits it.
 * @property detectFormats annotate strings that look like `date-time`, `date`, `time`, `email`, `uuid`,
 * `uri` or `ipv4` with a `format` keyword.
 * @property pretty pretty-print the schema string.
 */
public data class JsonSchemaOptions(
    val title: String? = null,
    val description: String? = null,
    val schemaUri: String? = "https://json-schema.org/draft/2020-12/schema",
    val requireAllProperties: Boolean = true,
    val additionalProperties: Boolean? = null,
    val detectFormats: Boolean = true,
    val pretty: Boolean = true,
) {
    public companion object {
        public val Default: JsonSchemaOptions = JsonSchemaOptions()
    }
}

/**
 * Infers a JSON Schema (draft 2020-12) that describes this value and returns it as a JSON string.
 *
 * Objects become `object` schemas with `properties`; arrays get an `items` schema merged from every element
 * (heterogeneous elements produce a `type` array, object elements merge their properties); integral numbers
 * are `integer`, other numbers `number`.
 */
public fun JsonValue.toJsonSchema(options: JsonSchemaOptions = JsonSchemaOptions.Default): String =
    toJsonSchemaValue(options).toJson(JsonFormat(pretty = options.pretty))

/** Same as [toJsonSchema] but returns the schema as a [JsonObject]. */
public fun JsonValue.toJsonSchemaValue(options: JsonSchemaOptions = JsonSchemaOptions.Default): JsonObject {
    val inferred = SchemaInference(options).infer(this)
    val root = LinkedHashMap<String, JsonValue>()
    options.schemaUri?.let { root["\$schema"] = JsonString(it) }
    options.title?.let { root["title"] = JsonString(it) }
    options.description?.let { root["description"] = JsonString(it) }
    root.putAll(inferred.render(options).fields)
    return JsonObject(root)
}

/** Parses [json] and infers its schema. Convenience for `Json.parse(json).toJsonSchema(options)`. */
public fun inferJsonSchema(json: String, options: JsonSchemaOptions = JsonSchemaOptions.Default): String =
    Json.parse(json).toJsonSchema(options)

private class InferredSchema {
    val types = LinkedHashSet<String>()
    var properties: LinkedHashMap<String, InferredSchema>? = null
    var required: LinkedHashSet<String>? = null
    var items: InferredSchema? = null
    var format: String? = null
    var formatConflict = false

    fun mergeWith(other: InferredSchema): InferredSchema {
        // `format` only makes sense while every string seen so far agreed on it.
        if ("string" in other.types) {
            if ("string" !in types) {
                format = other.format
                formatConflict = other.formatConflict
            } else if (other.formatConflict || format != other.format) {
                format = null
                formatConflict = true
            }
        }

        types += other.types

        val op = other.properties
        if (op != null) {
            val mine = properties
            if (mine == null) {
                properties = op
                required = other.required
            } else {
                for ((k, v) in op) {
                    val existing = mine[k]
                    mine[k] = if (existing == null) v else existing.mergeWith(v)
                }
                val r = required
                val or = other.required
                required = when {
                    r == null -> or
                    or == null -> r
                    else -> LinkedHashSet(r.filter { it in or })
                }
            }
        }

        val oi = other.items
        if (oi != null) {
            val mine = items
            items = if (mine == null) oi else mine.mergeWith(oi)
        }
        return this
    }

    fun render(options: JsonSchemaOptions): JsonObject {
        val out = LinkedHashMap<String, JsonValue>()
        when (types.size) {
            0 -> {}
            1 -> out["type"] = JsonString(types.first())
            else -> out["type"] = JsonArray(types.map { JsonString(it) })
        }
        format?.let { out["format"] = JsonString(it) }
        val props = properties
        if (props != null && "object" in types) {
            if (props.isNotEmpty()) {
                out["properties"] = JsonObject(props.mapValuesTo(LinkedHashMap()) { (_, v) -> v.render(options) })
            }
            val req = required
            if (options.requireAllProperties && !req.isNullOrEmpty()) {
                out["required"] = JsonArray(req.map { JsonString(it) })
            }
            options.additionalProperties?.let { out["additionalProperties"] = JsonBool.of(it) }
        }
        val it = items
        if (it != null && "array" in types) {
            out["items"] = it.render(options)
        }
        return JsonObject(out)
    }
}

private class SchemaInference(private val options: JsonSchemaOptions) {
    fun infer(value: JsonValue): InferredSchema {
        val s = InferredSchema()
        when (value) {
            JsonNull -> s.types += "null"
            is JsonBool -> s.types += "boolean"
            is JsonNumber -> s.types += if (value.isIntegral) "integer" else "number"
            is JsonString -> {
                s.types += "string"
                if (options.detectFormats) s.format = detectFormat(value.value)
            }
            is JsonArray -> {
                s.types += "array"
                var merged: InferredSchema? = null
                for (item in value.items) {
                    val inferred = infer(item)
                    merged = merged?.mergeWith(inferred) ?: inferred
                }
                s.items = merged
            }
            is JsonObject -> {
                s.types += "object"
                s.properties = value.fields.mapValuesTo(LinkedHashMap()) { (_, v) -> infer(v) }
                s.required = LinkedHashSet(value.fields.keys)
            }
        }
        return s
    }

    private fun detectFormat(s: String): String? = when {
        s.isEmpty() || s.length > 2048 -> null
        DATE_TIME.matches(s) -> "date-time"
        DATE.matches(s) -> "date"
        TIME.matches(s) -> "time"
        UUID.matches(s) -> "uuid"
        EMAIL.matches(s) -> "email"
        IPV4.matches(s) -> "ipv4"
        URI.matches(s) -> "uri"
        else -> null
    }

    private companion object {
        val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        val TIME = Regex("""\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})?""")
        val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
        val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
        val URI = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    }
}
