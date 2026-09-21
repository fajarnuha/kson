package com.fajarnuha.kson

/** Builds a draft 2020-12 JSON Schema. Use [schema] for reusable subschemas. */
public fun jsonSchema(block: JsonSchemaBuilder.() -> Unit): JsonObject = JsonSchemaBuilder().apply(block).build(root = true)

/** Builds a subschema without a `$schema` declaration. */
public fun schema(block: JsonSchemaBuilder.() -> Unit): JsonObject = JsonSchemaBuilder().apply(block).build()

@JsonDsl
public class JsonSchemaBuilder internal constructor() {
    private val fields = LinkedHashMap<String, JsonValue>()
    private val properties = LinkedHashMap<String, JsonValue>()
    private val required = LinkedHashSet<String>()
    private val definitions = LinkedHashMap<String, JsonValue>()

    public fun title(value: String) { fields["title"] = JsonString(value) }
    public fun description(value: String) { fields["description"] = JsonString(value) }
    public fun type(vararg names: String) {
        require(names.isNotEmpty() && names.size == names.toSet().size && names.all { it in TYPES })
        fields["type"] = if (names.size == 1) JsonString(names[0]) else JsonArray(names.map(::JsonString))
    }

    public fun property(name: String, required: Boolean = false, block: JsonSchemaBuilder.() -> Unit) {
        require(name !in properties) { "Duplicate schema property: $name" }
        properties[name] = schema(block)
        if (required) this.required += name
        if ("type" !in fields) fields["type"] = JsonString("object")
    }

    public fun items(block: JsonSchemaBuilder.() -> Unit) {
        fields["items"] = schema(block)
        if ("type" !in fields) fields["type"] = JsonString("array")
    }

    public fun additionalProperties(allowed: Boolean) { fields["additionalProperties"] = JsonBool.of(allowed) }
    public fun additionalProperties(block: JsonSchemaBuilder.() -> Unit) { fields["additionalProperties"] = schema(block) }
    public fun definition(name: String, block: JsonSchemaBuilder.() -> Unit) {
        require(name !in definitions) { "Duplicate schema definition: $name" }
        definitions[name] = schema(block)
    }
    public fun ref(pointer: String) { fields["\$ref"] = JsonString(pointer) }
    public fun oneOf(vararg schemas: JsonObject) { require(schemas.isNotEmpty()); fields["oneOf"] = JsonArray(schemas.toList()) }
    public fun anyOf(vararg schemas: JsonObject) { require(schemas.isNotEmpty()); fields["anyOf"] = JsonArray(schemas.toList()) }
    public fun allOf(vararg schemas: JsonObject) { require(schemas.isNotEmpty()); fields["allOf"] = JsonArray(schemas.toList()) }
    public fun enum(vararg values: Any?) {
        require(values.isNotEmpty()) { "enum needs at least one value" }
        val items = values.map { it.toJsonValue() }
        require(items.size == items.toSet().size) { "enum values must be unique" }
        fields["enum"] = JsonArray(items)
    }
    public fun const(value: Any?) { fields["const"] = value.toJsonValue() }
    public fun default(value: Any?) { fields["default"] = value.toJsonValue() }
    public fun format(value: String) { fields["format"] = JsonString(value) }
    public fun pattern(value: String) { fields["pattern"] = JsonString(value) }
    public fun minimum(value: Number) { fields["minimum"] = JsonNumber(value) }
    public fun maximum(value: Number) { fields["maximum"] = JsonNumber(value) }
    public fun minLength(value: Int) { require(value >= 0); fields["minLength"] = JsonNumber(value) }
    public fun maxLength(value: Int) { require(value >= 0); fields["maxLength"] = JsonNumber(value) }
    public fun minItems(value: Int) { require(value >= 0); fields["minItems"] = JsonNumber(value) }
    public fun maxItems(value: Int) { require(value >= 0); fields["maxItems"] = JsonNumber(value) }
    public fun uniqueItems(value: Boolean) { fields["uniqueItems"] = JsonBool.of(value) }

    internal fun build(root: Boolean = false): JsonObject {
        val out = LinkedHashMap<String, JsonValue>()
        if (root) out["\$schema"] = JsonString("https://json-schema.org/draft/2020-12/schema")
        out.putAll(fields)
        if (properties.isNotEmpty()) out["properties"] = JsonObject(properties)
        if (required.isNotEmpty()) out["required"] = JsonArray(required.map(::JsonString))
        if (definitions.isNotEmpty()) out["\$defs"] = JsonObject(definitions)
        return JsonObject(out)
    }

    private companion object {
        val TYPES = setOf("null", "boolean", "object", "array", "number", "integer", "string")
    }
}
