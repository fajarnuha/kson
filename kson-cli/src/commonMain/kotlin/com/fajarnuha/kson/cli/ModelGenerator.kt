package com.fajarnuha.kson.cli

import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonSchemaOptions
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.toJsonSchemaValue

internal fun renderKsonModel(value: JsonValue, requestedName: String, packageName: String?): String {
    if (packageName != null && (!PACKAGE.matches(packageName) || packageName.split('.').any { it in KEYWORDS })) {
        throw UsageException("Invalid Kotlin package name: $packageName")
    }
    val schema = value.toJsonSchemaValue(
        JsonSchemaOptions(schemaUri = null, detectFormats = false, pretty = false),
    )
    if (schema.typeNames() != setOf("object")) {
        throw UsageException("model needs a JSON object at the root")
    }
    val model = buildModel(requestedName.typeName(), schema)
    return buildString {
        if (packageName != null) appendLine("package $packageName\n")
        appendLine("import com.fajarnuha.kson.Kson")
        if (model.usesJsonValue()) appendLine("import com.fajarnuha.kson.JsonValue")
        appendLine()
        appendModel(model, annotation = true)
    }.trimEnd()
}

private data class Model(
    val name: String,
    val properties: List<Property>,
    val nested: List<Model>,
)

private data class Property(val name: String, val type: String)

private fun buildModel(name: String, schema: JsonObject): Model {
    val nested = mutableListOf<Model>()
    val usedNames = mutableSetOf(name)
    val required = (schema.fields["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonString)?.value }
        ?.toSet()
        .orEmpty()
    val properties = (schema.fields["properties"] as? JsonObject)?.fields.orEmpty().map { (propertyName, value) ->
        val propertySchema = value as JsonObject
        val suggestedName = uniqueName(propertyName.typeName(), usedNames)
        val nullable = propertyName !in required || "null" in propertySchema.typeNames()
        val type = kotlinType(propertySchema, suggestedName, nested)
        Property(propertyName.propertyName(), type + if (nullable && !type.endsWith("?")) "?" else "")
    }
    return Model(name, properties, nested)
}

private fun kotlinType(schema: JsonObject, suggestedName: String, nested: MutableList<Model>): String {
    val types = schema.typeNames() - "null"
    return when {
        types == setOf("object") -> suggestedName.also { nested += buildModel(it, schema) }
        types == setOf("array") -> {
            val itemSchema = schema.fields["items"] as? JsonObject
            val itemType = itemSchema?.let { kotlinType(it, "${suggestedName}Item", nested) } ?: "JsonValue"
            val nullableItem = itemSchema != null && "null" in itemSchema.typeNames()
            "List<$itemType${if (nullableItem && !itemType.endsWith("?")) "?" else ""}>"
        }
        types == setOf("string") -> "String"
        types == setOf("boolean") -> "Boolean"
        types == setOf("integer") -> "Long"
        types == setOf("number") || types == setOf("integer", "number") -> "Double"
        else -> "JsonValue"
    }
}

private fun StringBuilder.appendModel(model: Model, annotation: Boolean, indent: String = "") {
    if (annotation) appendLine("${indent}@Kson")
    if (model.properties.isEmpty() && model.nested.isEmpty()) {
        appendLine("${indent}public interface ${model.name}")
        return
    }
    appendLine("${indent}public interface ${model.name} {")
    model.properties.forEach { appendLine("$indent    public val ${it.name}: ${it.type}") }
    model.nested.forEach {
        appendLine()
        appendModel(it, annotation = false, indent = "$indent    ")
    }
    appendLine("$indent}")
}

private fun Model.usesJsonValue(): Boolean =
    properties.any { "JsonValue" in it.type } || nested.any(Model::usesJsonValue)

private fun JsonObject.typeNames(): Set<String> = when (val type = fields["type"]) {
    is JsonString -> setOf(type.value)
    is JsonArray -> type.mapNotNullTo(linkedSetOf()) { (it as? JsonString)?.value }
    else -> emptySet()
}

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

private fun uniqueName(base: String, used: MutableSet<String>): String {
    var name = base
    var suffix = 2
    while (!used.add(name)) name = "$base${suffix++}"
    return name
}

private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)
