package com.fajarnuha.kson.ksp

import com.google.devtools.ksp.isAbstract
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

    private val reached = mutableMapOf<String, Set<String>>()

    private fun generate(root: KSClassDeclaration) {
        require(root.typeParameters.isEmpty()) { "@Kson interfaces cannot have type parameters" }
        val packageName = root.packageName.asString()
        val rootName = root.simpleName.asString()
        val models = readModels(root)
        models.values.forEach { model ->
            model.owner = ownerOf(model.declaration, root)
            model.local = model.owner.qualifiedName?.asString() == root.qualifiedName?.asString()
        }
        // Models owned by another @Kson interface are decoded through its generated object, so only
        // follow references until they leave this root.
        val owned = linkedSetOf<Model>()
        fun visit(model: Model) {
            if (!model.local || !owned.add(model)) return
            model.properties.forEach { property -> property.type.models().forEach(::visit) }
        }
        val rootModel = models.getValue(root.qualifiedName!!.asString())
        visit(rootModel)
        val source = buildSource(packageName, rootName, rootModel, owned.toList())
        val sourceFiles = models.values.mapNotNull { it.declaration.containingFile }.distinct().toTypedArray()
        require(sourceFiles.isNotEmpty()) { "@Kson interface must be declared in source" }
        codeGenerator.createNewFile(
            Dependencies(aggregating = false, *sourceFiles),
            packageName,
            "${rootName}Json",
        ).bufferedWriter().use { it.write(source) }
    }

    /**
     * The @Kson interface whose generated object holds the codec for [declaration]: [declaration] itself when it is
     * a @Kson interface, else the nearest enclosing one that reaches it. Either must be declared in this compilation,
     * since other roots call its internal codec. Otherwise each root that reaches [declaration] gets its own copy.
     */
    private fun ownerOf(declaration: KSClassDeclaration, root: KSClassDeclaration): KSClassDeclaration {
        val name = declaration.qualifiedName!!.asString()
        return generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }
            .firstOrNull { candidate ->
                candidate.containingFile != null && candidate.isKson() && name in reachedFrom(candidate)
            }
            ?: root
    }

    private fun reachedFrom(declaration: KSClassDeclaration): Set<String> =
        reached.getOrPut(declaration.qualifiedName!!.asString()) {
            try {
                readModels(declaration).keys
            } catch (_: IllegalArgumentException) {
                emptySet()
            }
        }
}

/** Reads [root] and every interface it reaches, keyed by qualified name. */
private fun readModels(root: KSClassDeclaration): Map<String, Model> {
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
        val model = Model(declaration)
        models[qualifiedName] = model
        declaration.getAllProperties().forEach { property ->
            require(!property.isMutable) {
                "Kson properties must be val: ${property.simpleName.asString()}"
            }
            model.properties += Property(
                property.simpleName.asString(),
                readType(property.type.resolve(), ::readModel),
                hasDefault = !property.isAbstract(),
            )
        }
        return model
    }

    readModel(root)
    return models
}

private fun KSClassDeclaration.isKson(): Boolean = annotations.any {
    it.shortName.asString() == "Kson" &&
        it.annotationType.resolve().declaration.qualifiedName?.asString() == KSON_ANNOTATION
}

private class Model(
    val declaration: KSClassDeclaration,
    val properties: MutableList<Property> = mutableListOf(),
) {
    /** The @Kson interface whose generated object holds this model's codec and builder. */
    lateinit var owner: KSClassDeclaration

    /** Whether the root being generated is the [owner]. */
    var local: Boolean = true

    val typeName: String get() = declaration.qualifiedName!!.asString()

    val generatedName: String
        get() = typeName.removePrefix("${owner.packageName.asString()}.").replace('.', '_')

    /** Prefix that reaches this model's codec and builder from the root being generated. */
    val ref: String
        get() {
            if (local) return ""
            val packageName = owner.packageName.asString()
            return (if (packageName.isEmpty()) "" else "$packageName.") + "${owner.simpleName.asString()}Json."
        }
}

/** [hasDefault] is true when the interface gives the property a getter, which then supplies its default. */
private data class Property(val name: String, val type: TypeRef, val hasDefault: Boolean) {
    val required: Boolean get() = !type.nullable && !hasDefault
}

private data class TypeRef(val kind: TypeKind, val nullable: Boolean)

private sealed interface TypeKind {
    data class Scalar(
        val kotlinType: String,
        val decode: String,
        val schemaType: String?,
        val encode: String? = null,
    ) : TypeKind
    data class Object(val model: Model) : TypeKind
    data class ListType(val element: TypeRef) : TypeKind
    data class EnumType(val declaration: KSClassDeclaration) : TypeKind
}

private fun TypeRef.models(): List<Model> = when (val kind = kind) {
    is TypeKind.Object -> listOf(kind.model)
    is TypeKind.ListType -> kind.element.models()
    else -> emptyList()
}

private fun readType(type: KSType, readModel: (KSClassDeclaration) -> Model): TypeRef {
    val declaration = type.declaration as? KSClassDeclaration
        ?: throw IllegalArgumentException("Unsupported Kson property type: $type")
    val qualifiedName = declaration.qualifiedName?.asString()
        ?: throw IllegalArgumentException("Unsupported local Kson property type: $type")
    val nullable = type.nullability == Nullability.NULLABLE
    val kind = when (qualifiedName) {
        "kotlin.String" -> TypeKind.Scalar("String", ".string", "string", "JsonString")
        "kotlin.Boolean" -> TypeKind.Scalar("Boolean", ".boolean", "boolean", "JsonBool.of")
        "kotlin.Int" -> TypeKind.Scalar("Int", ".int", "integer", "JsonNumber")
        "kotlin.Long" -> TypeKind.Scalar("Long", ".long", "integer", "JsonNumber")
        "kotlin.Float" -> TypeKind.Scalar("Float", ".float", "number", "JsonNumber")
        "kotlin.Double" -> TypeKind.Scalar("Double", ".double", "number", "JsonNumber")
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
    appendLine("public object ${rootName}Json : KsonDecoder<${root.typeName}>, KsonEncoder<${root.typeName}> {")
    appendLine("    override fun decode(text: String): ${root.typeName} = decode(Json.parse(text))")
    appendLine("    override fun decode(value: JsonValue): ${root.typeName} = decode${root.generatedName}(value)")
    appendLine("    override fun encode(value: ${root.typeName}): JsonObject = encode${root.generatedName}(value)")
    appendLine()
    appendLine("    override val schema: JsonObject = jsonSchema {")
    appendLine("        title(${rootName.quoted()})")
    appendSchema(root, "        ", mutableSetOf())
    appendLine("    }")

    models.forEach { model ->
        appendLine()
        appendLine("    internal fun decode${model.generatedName}(value: JsonValue): ${model.typeName} {")
        appendLine("        val fields = value.jsonObject")
        appendLine("        val builder = ${model.generatedName}Builder()")
        model.properties.forEach { property ->
            val name = property.name.identifier()
            val key = property.name.quoted()
            when {
                property.required ->
                    appendLine("        builder.$name = ${decodeExpression(property.type, "fields.require($key)")}")
                // A missing key keeps the builder's default; a JSON null does too unless the property is nullable.
                property.type.nullable ->
                    appendLine("        fields[$key]?.let { item -> builder.$name = ${decodeExpression(property.type, "item")} }")
                else ->
                    appendLine("        fields[$key]?.takeUnless { it === JsonNull }?.let { item -> builder.$name = ${decodeExpression(property.type, "item")} }")
            }
        }
        appendLine("        return builder.build()")
        appendLine("    }")
        appendLine()
        if (model.properties.isEmpty()) {
            appendLine("    internal fun encode${model.generatedName}(value: ${model.typeName}): JsonObject = JsonObject.Empty")
        } else {
            appendLine("    internal fun encode${model.generatedName}(value: ${model.typeName}): JsonObject =")
            appendLine("        JsonObject(linkedMapOf<String, JsonValue>(")
            model.properties.forEach { property ->
                val encoded = encodeExpression(property.type, "value.${property.name.identifier()}")
                appendLine("            ${property.name.quoted()} to $encoded,")
            }
            appendLine("        ))")
        }
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
        appendLine()
        appendBuilder(model, packageName)
    }
    appendLine("}")
    appendLine()
    val builderFunction = rootName.replaceFirstChar { it.lowercaseChar() } + "Kson"
    appendLine("/** Builds a [${root.typeName}] with a DSL. Reading or building a required property that was never set throws [IllegalStateException]. */")
    appendLine("public fun $builderFunction(block: ${rootName}Json.${root.generatedName}Builder.() -> Unit): ${root.typeName} =")
    appendLine("    ${rootName}Json.${root.generatedName}Builder().apply(block).build()")
}

private fun StringBuilder.appendBuilder(model: Model, packageName: String) {
    val owner = model.typeName.removePrefix("$packageName.").quoted()
    val builder = "${model.generatedName}Builder"
    appendLine("    @JsonDsl")
    appendLine("    public class $builder internal constructor() {")
    model.properties.forEachIndexed { index, property ->
        val name = property.name.identifier()
        val type = renderType(property.type)
        val delegate = when {
            property.hasDefault -> "KsonProperty<$type>($owner) { KsonDefaults().ksonDefault$index() }"
            property.type.nullable -> "KsonProperty<$type>($owner) { null }"
            else -> "KsonProperty<$type>($owner)"
        }
        appendLine("        public var $name: $type by $delegate")
        when (val kind = property.type.kind) {
            is TypeKind.Object -> {
                val nested = "${kind.model.ref}${kind.model.generatedName}Builder"
                appendLine()
                appendLine("        public fun $name(block: $nested.() -> Unit) {")
                appendLine("            $name = $nested().apply(block).build()")
                appendLine("        }")
            }
            is TypeKind.ListType -> (kind.element.kind as? TypeKind.Object)?.let { element ->
                val nested = "${element.model.ref}${element.model.generatedName}Builder"
                val listBuilder = "KsonListBuilder<${renderType(kind.element)}, $nested>"
                appendLine()
                appendLine("        public fun $name(block: $listBuilder.() -> Unit) {")
                appendLine("            $name = $listBuilder({ $nested() }, { it.build() }).apply(block).toList()")
                appendLine("        }")
            }
            else -> Unit
        }
        appendLine()
    }
    if (model.properties.any { it.hasDefault }) {
        // Implements the interface over this builder so an interface getter can supply a default,
        // reading any other property it needs from the builder.
        appendLine("        private inner class KsonDefaults : ${model.typeName} {")
        model.properties.forEach { property ->
            val name = property.name.identifier()
            appendLine("            override val $name: ${renderType(property.type)} get() = this@$builder.$name")
        }
        model.properties.forEachIndexed { index, property ->
            if (property.hasDefault) {
                appendLine()
                appendLine("            fun ksonDefault$index(): ${renderType(property.type)} = super<${model.typeName}>.${property.name.identifier()}")
            }
        }
        appendLine("        }")
        appendLine()
    }
    appendLine("        internal fun build(): ${model.typeName} = ${model.generatedName}Impl(")
    model.properties.forEach { property ->
        appendLine("            ${property.name.identifier()} = ${property.name.identifier()},")
    }
    appendLine("        )")
    appendLine("    }")
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
        appendLine("${indent}property(${property.name.quoted()}, required = ${property.required}) {")
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
        is TypeKind.Object -> "${kind.model.ref}decode${kind.model.generatedName}($value)"
        is TypeKind.ListType -> "$value.jsonArray.map { item -> ${decodeExpression(kind.element, "item")} }"
        is TypeKind.EnumType -> "$value.enumValue<${kind.declaration.qualifiedName!!.asString()}>()"
    }
    return if (type.nullable) "$value.takeUnless { it === JsonNull }?.let { item -> ${decodeExpression(type.copy(nullable = false), "item")} }" else decoded
}

private fun encodeExpression(type: TypeRef, value: String): String {
    if (type.nullable) {
        return "($value?.let { item -> ${encodeExpression(type.copy(nullable = false), "item")} } ?: JsonNull)"
    }
    return when (val kind = type.kind) {
        is TypeKind.Scalar -> kind.encode?.let { "$it($value)" } ?: value
        is TypeKind.Object -> "${kind.model.ref}encode${kind.model.generatedName}($value)"
        is TypeKind.ListType -> "JsonArray($value.map { item -> ${encodeExpression(kind.element, "item")} })"
        is TypeKind.EnumType -> "JsonString($value.name)"
    }
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
