package com.fajarnuha.kson

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap

/**
 * Returns the decoder that kson-ksp generated for a [Kson] interface, or null when [type] is not annotated
 * with [Kson]. The decoder for `com.example.User` is the object `com.example.UserJson`.
 *
 * @throws IllegalStateException when [type] is annotated but its decoder was not generated.
 */
public fun ksonDecoderOf(type: Class<*>): KsonDecoder<*>? =
    generatedFor(type)?.let { it as? KsonDecoder<*> ?: throw outdated(it, KsonDecoder::class.java) }

/**
 * Returns the encoder that kson-ksp generated for [type] when it is a [Kson] interface or implements exactly
 * one, or null otherwise.
 *
 * @throws IllegalStateException when the [Kson] interface's encoder was not generated.
 */
public fun ksonEncoderOf(type: Class<*>): KsonEncoder<*>? {
    val ksonType = ksonTypes.getOrPut(type) { type.ksonInterfaces().singleOrNull() ?: None } as? Class<*>
    return ksonType?.let(::generatedFor)?.let { it as? KsonEncoder<*> ?: throw outdated(it, KsonEncoder::class.java) }
}

/**
 * Returns a function that converts a parsed JSON value to [type], or null when kson has no mapping for it.
 *
 * Supported types are [Kson] interfaces, [JsonValue] and its subtypes, and `List`, `Collection`, or `Iterable`
 * of a supported type. JSON `null` becomes Kotlin `null` for [Kson] interfaces.
 */
public fun ksonReaderOf(type: Type): ((JsonValue) -> Any?)? = when (type) {
    is Class<*> -> if (JsonValue::class.java.isAssignableFrom(type)) {
        { value -> value.requireType(type) }
    } else {
        ksonDecoderOf(type)?.let { decoder -> { value -> if (value === JsonNull) null else decoder.decode(value) } }
    }
    is ParameterizedType -> if (type.rawType in collectionTypes) {
        ksonReaderOf(type.actualTypeArguments.single())?.let { element ->
            { value -> value.jsonArray.map(element) }
        }
    } else {
        null
    }
    is WildcardType -> type.upperBounds.singleOrNull()?.let(::ksonReaderOf)
    else -> null
}

/**
 * Returns a function that converts a value of [type] to JSON, or null when kson has no mapping for it.
 *
 * Supported types are those of [ksonReaderOf], plus classes that implement exactly one [Kson] interface.
 * Kotlin `null` becomes JSON `null`.
 */
public fun ksonWriterOf(type: Type): ((Any?) -> JsonValue)? = when (type) {
    is Class<*> -> if (JsonValue::class.java.isAssignableFrom(type)) {
        { value -> value as JsonValue? ?: JsonNull }
    } else {
        @Suppress("UNCHECKED_CAST")
        (ksonEncoderOf(type) as KsonEncoder<Any>?)?.let { encoder ->
            { value -> if (value == null) JsonNull else encoder.encode(value) }
        }
    }
    is ParameterizedType -> if (type.rawType in collectionTypes) {
        ksonWriterOf(type.actualTypeArguments.single())?.let { element ->
            { value -> if (value == null) JsonNull else JsonArray((value as Iterable<*>).map(element)) }
        }
    } else {
        null
    }
    is WildcardType -> type.upperBounds.singleOrNull()?.let(::ksonWriterOf)
    else -> null
}

private val generated = ConcurrentHashMap<Class<*>, Any>()

private val ksonTypes = ConcurrentHashMap<Class<*>, Any>()

private object None

private val collectionTypes = setOf(List::class.java, Collection::class.java, Iterable::class.java)

private fun generatedFor(type: Class<*>): Any? =
    generated.getOrPut(type) { findGenerated(type) ?: None }.takeUnless { it === None }

private fun findGenerated(type: Class<*>): Any? {
    if (!type.isAnnotationPresent(Kson::class.java)) return null
    val packageName = type.name.substringBeforeLast('.', "")
    val generatedName = if (packageName.isEmpty()) "${type.simpleName}Json" else "$packageName.${type.simpleName}Json"
    return try {
        Class.forName(generatedName, true, type.classLoader).getField("INSTANCE").get(null)
    } catch (error: ReflectiveOperationException) {
        throw IllegalStateException(
            "No generated decoder $generatedName for @Kson ${type.name}; apply kson-ksp to the module that declares it",
            error,
        )
    }
}

private fun outdated(generated: Any, expected: Class<*>) =
    IllegalStateException("${generated.javaClass.name} is not a ${expected.simpleName}; rebuild it with a matching kson-ksp")

private fun Class<*>.ksonInterfaces(): Set<Class<*>> = buildSet {
    fun visit(type: Class<*>?) {
        if (type == null) return
        if (type.isAnnotationPresent(Kson::class.java)) add(type)
        type.interfaces.forEach(::visit)
        visit(type.superclass)
    }
    visit(this@ksonInterfaces)
}

private fun JsonValue.requireType(type: Class<*>): JsonValue =
    if (type.isInstance(this)) this else throw JsonTypeException("Expected ${type.simpleName} but found $jsonType")
