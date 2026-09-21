package com.fajarnuha.kson.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.validate

public class KsonProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KsonProcessor(environment.codeGenerator, environment.logger)
}

private class KsonProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val generated = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(KSON_ANNOTATION).toList()
        symbols.filter { it.validate() }.forEach { symbol ->
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null || declaration.classKind != ClassKind.INTERFACE) {
                logger.error("@Kson can only annotate an interface", symbol)
            } else if (declaration.qualifiedName?.asString() !in generated) {
                try {
                    generate(declaration)
                    generated += declaration.qualifiedName!!.asString()
                } catch (error: IllegalArgumentException) {
                    logger.error(error.message ?: "Unable to generate Kson decoder", declaration)
                }
            }
        }
        return symbols.filterNot { it.validate() }
    }

    private fun generate(root: KSClassDeclaration) {
        require(root.typeParameters.isEmpty()) { "@Kson interfaces cannot have type parameters" }
        val packageName = root.packageName.asString()
        val rootName = root.simpleName.asString()
        val models = linkedMapOf<String, Model>()

        fun readModel(declaration: KSClassDeclaration): Model {
            val qualifiedName = requireNotNull(declaration.qualifiedName?.asString()) {
                "@Kson interfaces must have a qualified name"
            }
            models[qualifiedName]?.let { return it }
            require(declaration.classKind == ClassKind.INTERFACE) {
                "Unsupported property type $qualifiedName: use an interface for nested JSON objects"
            }
            require(declaration.typeParameters.isEmpty()) {
                "Nested Kson interfaces cannot have type parameters: $qualifiedName"
            }
            val model = Model(
                declaration = declaration,
                generatedName = qualifiedName.removePrefix("$packageName.").replace('.', '_'),
            )
            models[qualifiedName] = model
            declaration.getAllProperties().forEach { property ->
                require(!property.isMutable) {
                    "Kson properties must be val: ${property.simpleName.asString()}"
                }
                model.properties += Property(
                    property.simpleName.asString(),
                    readType(property.type.resolve(), ::readModel),
                )
            }
            return model
        }

        val rootModel = readModel(root)
        val source = buildSource(packageName, rootName, rootModel, models.values.toList())
        val sourceFiles = models.values.mapNotNull { it.declaration.containingFile }.distinct().toTypedArray()
        require(sourceFiles.isNotEmpty()) { "@Kson interface must be declared in source" }
        codeGenerator.createNewFile(
            Dependencies(aggregating = false, *sourceFiles),
            packageName,
            "${rootName}Json",
        ).bufferedWriter().use { it.write(source) }
    }
}

private class Model(
    val declaration: KSClassDeclaration,
    val generatedName: String,
    val properties: MutableList<Property> = mutableListOf(),
) {
    val typeName: String get() = declaration.qualifiedName!!.asString()
}

private data class Property(val name: String, val type: TypeRef)

private data class TypeRef(val kind: TypeKind, val nullable: Boolean)

private sealed interface TypeKind {
    data class Scalar(val kotlinType: String, val decode: String, val schemaType: String?) : TypeKind
    data class Object(val model: Model) : TypeKind
    data class ListType(val element: TypeRef) : TypeKind
    data class EnumType(val declaration: KSClassDeclaration) : TypeKind
}

private fun readType(type: KSType, readModel: (KSClassDeclaration) -> Model): TypeRef {
    val declaration = type.declaration as? KSClassDeclaration
        ?: throw IllegalArgumentException("Unsupported Kson property type: $type")
    val qualifiedName = declaration.qualifiedName?.asString()
        ?: throw IllegalArgumentException("Unsupported local Kson property type: $type")
    val nullable = type.nullability == Nullability.NULLABLE
    val kind = when (qualifiedName) {
        "kotlin.String" -> TypeKind.Scalar("String", ".string", "string")
        "kotlin.Boolean" -> TypeKind.Scalar("Boolean", ".boolean", "boolean")
        "kotlin.Int" -> TypeKind.Scalar("Int", ".int", "integer")
        "kotlin.Long" -> TypeKind.Scalar("Long", ".long", "integer")
        "kotlin.Float" -> TypeKind.Scalar("Float", ".float", "number")
        "kotlin.Double" -> TypeKind.Scalar("Double", ".double", "number")
        "com.fajarnuha.kson.JsonNumber" -> TypeKind.Scalar(qualifiedName, ".number", "number")
        "com.fajarnuha.kson.JsonObject" -> TypeKind.Scalar(qualifiedName, ".jsonObject", "object")
        "com.fajarnuha.kson.JsonArray" -> TypeKind.Scalar(qualifiedName, ".jsonArray", "array")
        "com.fajarnuha.kson.JsonValue" -> TypeKind.Scalar(qualifiedName, "", null)
        "kotlin.collections.List" -> {
            val element = type.arguments.singleOrNull()?.type?.resolve()
                ?: throw IllegalArgumentException("Kson List properties need one concrete element type")
            TypeKind.ListType(readType(element, readModel))
        }
        else -> when (declaration.classKind) {
            ClassKind.INTERFACE -> TypeKind.Object(readModel(declaration))
            ClassKind.ENUM_CLASS -> TypeKind.EnumType(declaration)
            else -> throw IllegalArgumentException("Unsupported Kson property type: $qualifiedName")
        }
    }
    return TypeRef(kind, nullable)
}

private fun buildSource(
    packageName: String,
    rootName: String,
    root: Model,
    models: List<Model>,
): String = buildString {
    appendLine("package $packageName")
    appendLine()
    appendLine("import com.fajarnuha.kson.*")
    appendLine()
    appendLine("public object ${rootName}Json {")
    appendLine("    public fun decode(text: String): ${root.typeName} = decode(Json.parse(text))")
    appendLine("    public fun decode(value: JsonValue): ${root.typeName} = decode${root.generatedName}(value)")
    appendLine()
    appendLine("    public val schema: JsonObject = jsonSchema {")
    appendLine("        title(${rootName.quoted()})")
    appendSchema(root, "        ", mutableSetOf())
    appendLine("    }")

    models.forEach { model ->
        appendLine()
        appendLine("    private fun decode${model.generatedName}(value: JsonValue): ${model.typeName} =")
        appendLine("        ${model.generatedName}Impl(")
        model.properties.forEach { property ->
            append("            ${property.name.identifier()} = ")
            val lookup = if (property.type.nullable) {
                "value.jsonObject[${property.name.quoted()}]?.takeUnless { it === JsonNull }?.let { item -> ${decodeExpression(property.type.copy(nullable = false), "item")} }"
            } else {
                decodeExpression(property.type, "value.jsonObject.require(${property.name.quoted()})")
            }
            appendLine("$lookup,")
        }
        appendLine("        )")
        appendLine()
        if (model.properties.isEmpty()) {
            appendLine("    private class ${model.generatedName}Impl : ${model.typeName}")
        } else {
            appendLine("    private data class ${model.generatedName}Impl(")
            model.properties.forEach { property ->
                appendLine("        override val ${property.name.identifier()}: ${renderType(property.type)},")
            }
            appendLine("    ) : ${model.typeName}")
        }
    }
    appendLine("}")
}

private fun StringBuilder.appendSchema(
    model: Model,
    indent: String,
    visiting: MutableSet<String>,
    includeType: Boolean = true,
) {
    require(visiting.add(model.typeName)) { "Recursive Kson interfaces are not supported: ${model.typeName}" }
    if (includeType) appendLine("${indent}type(\"object\")")
    model.properties.forEach { property ->
        appendLine("${indent}property(${property.name.quoted()}, required = ${!property.type.nullable}) {")
        appendTypeSchema(property.type, "$indent    ", visiting)
        appendLine("$indent}")
    }
    visiting.remove(model.typeName)
}

private fun StringBuilder.appendTypeSchema(type: TypeRef, indent: String, visiting: MutableSet<String>) {
    val nullSuffix = if (type.nullable) ", \"null\"" else ""
    when (val kind = type.kind) {
        is TypeKind.Scalar -> if (kind.schemaType != null) {
            appendLine("${indent}type(\"${kind.schemaType}\"$nullSuffix)")
        }
        is TypeKind.Object -> {
            if (type.nullable) appendLine("${indent}type(\"object\", \"null\")")
            appendSchema(kind.model, indent, visiting, includeType = !type.nullable)
        }
        is TypeKind.ListType -> {
            if (type.nullable) appendLine("${indent}type(\"array\", \"null\")")
            appendLine("${indent}items {")
            appendTypeSchema(kind.element, "$indent    ", visiting)
            appendLine("$indent}")
        }
        is TypeKind.EnumType -> {
            appendLine("${indent}type(\"string\"$nullSuffix)")
            val entries = kind.declaration.declarations
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.ENUM_ENTRY }
                .map { it.simpleName.asString().quoted() }
                .plus(if (type.nullable) listOf("null") else emptyList())
                .joinToString()
            appendLine("${indent}enum($entries)")
        }
    }
}

private fun decodeExpression(type: TypeRef, value: String): String {
    val decoded = when (val kind = type.kind) {
        is TypeKind.Scalar -> value + kind.decode
        is TypeKind.Object -> "decode${kind.model.generatedName}($value)"
        is TypeKind.ListType -> "$value.jsonArray.map { item -> ${decodeExpression(kind.element, "item")} }"
        is TypeKind.EnumType -> "enumValueOf<${kind.declaration.qualifiedName!!.asString()}>($value.string)"
    }
    return if (type.nullable) "$value.takeUnless { it === JsonNull }?.let { item -> ${decodeExpression(type.copy(nullable = false), "item")} }" else decoded
}

private fun renderType(type: TypeRef): String {
    val name = when (val kind = type.kind) {
        is TypeKind.Scalar -> kind.kotlinType
        is TypeKind.Object -> kind.model.typeName
        is TypeKind.ListType -> "List<${renderType(kind.element)}>"
        is TypeKind.EnumType -> kind.declaration.qualifiedName!!.asString()
    }
    return name + if (type.nullable) "?" else ""
}

private fun String.identifier(): String = "`$this`"

private fun String.quoted(): String = buildString {
    append('"')
    this@quoted.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

private const val KSON_ANNOTATION = "com.fajarnuha.kson.Kson"
