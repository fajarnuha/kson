package com.fajarnuha.kson.cli

import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonBool
import com.fajarnuha.kson.JsonNull
import com.fajarnuha.kson.JsonNumber
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonSchemaOptions
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.at
import com.fajarnuha.kson.toJsonSchemaValue

/**
 * Renders an @Kson interface for [value], which is either a sample JSON object or a JSON Schema.
 * [fromSchema] forces schema mode; when null, a root object with `$schema` and a schema keyword is treated as a schema.
 * The root interface is named [name], then the schema's `title`, then `Model`.
 */
internal fun renderKsonModel(value: JsonValue, name: String?, packageName: String?, fromSchema: Boolean? = null): String {
    if (packageName != null && (!PACKAGE.matches(packageName) || packageName.split('.').any { it in KEYWORDS })) {
        throw UsageException("Invalid Kotlin package name: $packageName")
    }
    val isSchema = fromSchema ?: value.looksLikeSchema()
    val schema = if (isSchema) {
        value as? JsonObject ?: throw UsageException("model needs a JSON Schema object at the root")
    } else {
        value.toJsonSchemaValue(JsonSchemaOptions(schemaUri = null, detectFormats = false, pretty = false))
    }
    val rootName = (name ?: (schema.fields["title"] as? JsonString)?.value ?: "Model").typeName()
    val model = Generator(schema, rootName, inferred = !isSchema).generate()
    return buildString {
        if (packageName != null) appendLine("package $packageName\n")
        appendLine("import com.fajarnuha.kson.Kson")
        if (model.uses("JsonObject")) appendLine("import com.fajarnuha.kson.JsonObject")
        if (model.uses("JsonValue")) appendLine("import com.fajarnuha.kson.JsonValue")
        appendLine()
        appendModel(model, annotation = true)
    }.trimEnd()
}

private fun JsonValue.looksLikeSchema(): Boolean =
    this is JsonObject && fields["\$schema"] is JsonString && SCHEMA_KEYWORDS.any { it in fields }

private val SCHEMA_KEYWORDS = listOf(
    "type", "properties", "\$ref", "\$defs", "definitions", "allOf", "anyOf", "oneOf", "items",
)

private class Model(val name: String) {
    val properties = mutableListOf<Property>()
    val nested = mutableListOf<Model>()
}

private data class Property(val name: String, val type: String)

private data class Type(val name: String, val nullable: Boolean) {
    fun orNull(nullable: Boolean) = if (nullable) copy(nullable = true) else this
    override fun toString() = if (nullable) "$name?" else name
}

private val ANY = Type("JsonValue", nullable = true)

/**
 * Turns a JSON Schema into nested interface models.
 *
 * Inline object schemas become interfaces nested in the model that uses them, named after the property.
 * Object schemas reached through a local `$ref` become one shared interface nested in the root, so each is
 * generated once. Kson interfaces cannot be recursive, so a `$ref` back to an interface that is still being
 * generated becomes a [JsonObject] instead. [inferred] keeps the output of `kson model` on sample JSON
 * unchanged: there, an object without `properties` is an empty interface rather than an open [JsonObject].
 */
private class Generator(private val document: JsonObject, private val rootName: String, private val inferred: Boolean) {
    private val root = Model(rootName)

    /** Interface names given to `$ref` targets, keyed by the reference. */
    private val refNames = HashMap<String, String>()

    /** Names nested in the root for `$ref` targets; inline interfaces avoid them so they never shadow one. */
    private val reserved = mutableSetOf(rootName)

    /** Every interface name in the file, so a late `$ref` name never collides with an inline interface. */
    private val allNames = mutableSetOf(rootName)

    /** `$ref` targets whose interface is generated or being generated. */
    private val built = mutableSetOf<String>()

    /** `$ref` targets whose interface is being generated, i.e. that enclose the current property. */
    private val building = mutableSetOf<String>()

    /** `$ref` targets being resolved as non-object aliases, to stop alias cycles. */
    private val aliasing = mutableSetOf<String>()

    init {
        // Name the definitions up front so inline interfaces built before their first use avoid them.
        for (container in listOf("\$defs", "definitions")) {
            (document.fields[container] as? JsonObject)?.fields?.forEach { (key, schema) ->
                if (schema is JsonObject && objectSchema(schema) != null) refName("#/${escape(container)}/${escape(key)}", key)
            }
        }
    }

    fun generate(): Model {
        val rootRef = document.ref()
        val target = if (rootRef != null && "properties" !in document.fields) {
            refNames[rootRef]?.let { reserved -= it }
            refNames[rootRef] = rootName
            built += rootRef
            building += rootRef
            resolve(rootRef)
        } else {
            document
        }
        refNames["#"] = rootName
        built += "#"
        building += "#"
        val schema = (target as? JsonObject)?.let { objectSchema(it, root = true) }
            ?: throw UsageException(if (inferred) "model needs a JSON object at the root" else "model needs a JSON Schema that describes an object")
        fill(root, schema)
        return root
    }

    private fun fill(model: Model, schema: JsonObject) {
        val used = mutableSetOf(model.name)
        val required = schema.strings("required")
        (schema.fields["properties"] as? JsonObject)?.fields.orEmpty().forEach { (propertyName, propertySchema) ->
            val suggestedName = uniqueName(propertyName.typeName(), used, reserved)
            val type = typeOf(propertySchema, suggestedName, model).orNull(propertyName !in required)
            model.properties += Property(propertyName.propertyName(), type.toString())
        }
    }

    /** Builds the inline interface [name], nested in [parent]. */
    private fun inline(name: String, schema: JsonObject, parent: Model): Type {
        allNames += name
        val model = Model(name)
        parent.nested += model
        fill(model, schema)
        return Type(name, nullable = false)
    }

    private fun typeOf(value: JsonValue, suggestedName: String, parent: Model): Type {
        val schema = value as? JsonObject ?: return ANY
        val nullable = schema.allowsNull()

        schema.ref()?.let { return refType(it, parent).orNull(nullable) }

        val union = schema.array("anyOf") ?: schema.array("oneOf")
        if (union != null && !schema.hasObjectKeywords()) {
            val nonNull = union.filterNot { it.isNullSchema() }
            val unionNullable = nullable || nonNull.size < union.size
            val single = nonNull.singleOrNull() ?: return ANY.copy(nullable = unionNullable || nonNull.isEmpty())
            return typeOf(single, suggestedName, parent).orNull(unionNullable)
        }

        val allOf = schema.array("allOf")
        if (allOf != null && allOf.size == 1 && !schema.hasObjectKeywords()) {
            return typeOf(allOf.single(), suggestedName, parent).orNull(nullable)
        }

        objectSchema(schema)?.let { return inline(suggestedName, it, parent).orNull(nullable) }

        val types = schema.typeNames() - "null"
        val format = (schema.fields["format"] as? JsonString)?.value
        val name = when {
            types == setOf("object") -> if (inferred) return inline(suggestedName, schema, parent).orNull(nullable) else "JsonObject"
            types == setOf("array") -> {
                val items = schema.fields["items"]
                val item = if (items is JsonObject) typeOf(items, "${suggestedName}Item", parent) else Type("JsonValue", nullable = false)
                "List<$item>"
            }
            types == setOf("string") -> "String"
            types == setOf("boolean") -> "Boolean"
            types == setOf("integer") -> if (format == "int32") "Int" else "Long"
            types == setOf("number") && format == "float" -> "Float"
            types == setOf("number") || types == setOf("integer", "number") -> "Double"
            else -> return ANY.copy(nullable = nullable || types.isEmpty())
        }
        return Type(name, nullable)
    }

    private fun refType(ref: String, parent: Model): Type {
        val target = resolve(ref)
        if (target is JsonObject && objectSchema(target) != null) {
            val name = refName(ref, ref.substringAfterLast('/').unescape())
            if (ref in building) return Type("JsonObject", nullable = false)
            if (!built.add(ref)) return Type(name, nullable = false)
            val model = Model(name)
            root.nested += model
            building += ref
            fill(model, objectSchema(target)!!)
            building -= ref
            return Type(name, nullable = false)
        }
        if (!aliasing.add(ref)) return ANY
        try {
            return typeOf(target, ref.substringAfterLast('/').unescape().typeName(), parent)
        } finally {
            aliasing -= ref
        }
    }

    private fun refName(ref: String, base: String): String = refNames.getOrPut(ref) {
        uniqueName(base.typeName(), allNames).also { reserved += it }
    }

    private fun resolve(ref: String): JsonValue {
        if (!ref.startsWith("#")) throw UsageException("Only local \$ref values are supported: ${kotlinString(ref)}")
        return document.at(ref.substring(1).percentDecode())
            ?: throw UsageException("Cannot resolve \$ref ${kotlinString(ref)}")
    }

    /**
     * Returns the object schema [schema] describes, with `allOf` parts merged into one, or null when it does not
     * describe an object with known properties. The [root] may be an object without properties.
     */
    private fun objectSchema(schema: JsonObject, root: Boolean = false, seen: Set<String> = emptySet()): JsonObject? {
        val parts = schema.array("allOf")
        if (parts == null) {
            val types = schema.typeNames() - "null"
            val isObject = types == setOf("object") || (types.isEmpty() && "properties" in schema.fields)
            return schema.takeIf { isObject && (root || "properties" in schema.fields) }
        }
        val properties = LinkedHashMap<String, JsonValue>()
        val required = linkedSetOf<String>()
        for (part in parts + schema.copyWithout("allOf")) {
            var resolved = part
            var visited = seen
            while (resolved is JsonObject) {
                val ref = resolved.ref() ?: break
                if (ref in visited) return null
                visited = visited + ref
                resolved = resolve(ref)
            }
            if (resolved !is JsonObject) return null
            if (resolved.fields.keys.none { it in OBJECT_KEYWORDS }) {
                // Only constrains or annotates the other parts, e.g. `required` or OpenAPI's `nullable`.
                required += resolved.strings("required")
                continue
            }
            val merged = objectSchema(resolved, root = true, seen = visited) ?: return null
            (merged.fields["properties"] as? JsonObject)?.fields?.let { properties.putAll(it) }
            required += merged.strings("required")
        }
        if (properties.isEmpty() && !root) return null
        return JsonObject(
            linkedMapOf(
                "type" to JsonString("object"),
                "properties" to JsonObject(properties),
                "required" to JsonArray(required.map { JsonString(it) }),
            ),
        )
    }
}

private val OBJECT_KEYWORDS = setOf("type", "properties", "allOf")

private fun JsonObject.hasObjectKeywords() = "properties" in fields || "type" in fields

private fun JsonObject.copyWithout(key: String) = JsonObject(LinkedHashMap(fields).apply { remove(key) })

private fun JsonObject.ref(): String? = (fields["\$ref"] as? JsonString)?.value

private fun JsonObject.array(key: String): List<JsonValue>? = (fields[key] as? JsonArray)?.toList()

private fun JsonObject.strings(key: String): Set<String> =
    (fields[key] as? JsonArray)?.mapNotNullTo(linkedSetOf()) { (it as? JsonString)?.value }.orEmpty()

/** Types named by `type`, or implied by `enum` or `const` when `type` is absent. */
private fun JsonObject.typeNames(): Set<String> = when (val type = fields["type"]) {
    is JsonString -> setOf(type.value)
    is JsonArray -> type.mapNotNullTo(linkedSetOf()) { (it as? JsonString)?.value }
    else -> {
        val values = array("enum") ?: fields["const"]?.let(::listOf) ?: emptyList()
        values.mapTo(linkedSetOf(), ::jsonTypeOf).let { if ("number" in it) it - "integer" else it }
    }
}

private fun jsonTypeOf(value: JsonValue): String = when (value) {
    JsonNull -> "null"
    is JsonBool -> "boolean"
    is JsonNumber -> if (value.isIntegral) "integer" else "number"
    is JsonString -> "string"
    is JsonArray -> "array"
    is JsonObject -> "object"
}

/** Whether the schema itself admits null, through `type`, OpenAPI's `nullable`, `enum` or `const`. */
private fun JsonObject.allowsNull(): Boolean =
    "null" in typeNames() ||
        fields["nullable"] == JsonBool.True ||
        fields["x-nullable"] == JsonBool.True

private fun JsonValue.isNullSchema(): Boolean =
    this is JsonObject && typeNames() == setOf("null") && "\$ref" !in fields

private fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")

private fun String.unescape() = percentDecode().replace("~1", "/").replace("~0", "~")

private fun String.percentDecode(): String {
    if ('%' !in this) return this
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < length) {
        val hex = if (this[i] == '%' && i + 2 <= lastIndex) substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes += hex.toByte()
            i += 3
        } else {
            this[i].toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun StringBuilder.appendModel(model: Model, annotation: Boolean, indent: String = "") {
    if (annotation) appendLine("${indent}@Kson")
    if (model.properties.isEmpty() && model.nested.isEmpty()) {
        appendLine("${indent}interface ${model.name}")
        return
    }
    appendLine("${indent}interface ${model.name} {")
    model.properties.forEach { appendLine("$indent    val ${it.name}: ${it.type}") }
    model.nested.forEach {
        appendLine()
        appendModel(it, annotation = false, indent = "$indent    ")
    }
    appendLine("$indent}")
}

private fun Model.uses(type: String): Boolean =
    properties.any { Regex("\\b$type\\b").containsMatchIn(it.type) } || nested.any { it.uses(type) }

private fun String.typeName(): String {
    val words = split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)
    val result = words.joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }
        .ifEmpty { "Model" }
    return if (result.first().isDigit()) "_$result" else result
}

private fun String.propertyName(): String {
    if ('`' in this || '\n' in this || '\r' in this || isEmpty()) {
        throw UsageException("JSON key cannot be represented as a Kotlin property: ${kotlinString(this)}")
    }
    return if (IDENTIFIER.matches(this) && this !in KEYWORDS) this else "`$this`"
}

private fun uniqueName(base: String, used: MutableSet<String>, avoid: Set<String> = emptySet()): String {
    var name = base
    var suffix = 2
    while (name in avoid || !used.add(name)) name = "$base${suffix++}"
    return name
}

private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)
