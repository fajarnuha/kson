package com.fajarnuha.kson

import com.fajarnuha.kson.query.Checker
import com.fajarnuha.kson.query.Interpreter
import com.fajarnuha.kson.query.Item
import com.fajarnuha.kson.query.Node
import com.fajarnuha.kson.query.Prelude
import com.fajarnuha.kson.query.QueryParser

/** Thrown when a query does not compile. [offset] is the 0-based position in the filter text. */
public class JsonQuerySyntaxException(
    public val description: String,
    public val offset: Int,
) : JsonException("$description at offset $offset")

/**
 * Thrown when a query fails while running, either from `error(...)` or from an invalid operation such
 * as indexing a number. [value] is the error payload; it is a string for built-in errors.
 */
public class JsonQueryRuntimeException(public val value: JsonValue) :
    JsonException(if (value is JsonString) value.value else "${value.toJson()} (not a string)")

/**
 * A compiled jq program.
 *
 * Supports the jq language: paths (`.a.b`, `.[0]`, `.[]`, `.[1:3]`, `..`, `?`), pipes and commas, arithmetic
 * and comparisons, `and`/`or`/`//`, array and object construction, string interpolation and `@formats`,
 * `if`/`elif`/`else`, `try`/`catch`, `reduce`, `foreach`, `def` (with filter and `$` parameters),
 * variables and destructuring, assignment (`=`, `|=`, `+=`, ...), and the common builtin library
 * (`map`, `select`, `keys`, `to_entries`, `sort_by`, `group_by`, `test`, `sub`, `paths`, `del`, ...).
 *
 * ```kotlin
 * val names = JsonQuery.compile(".users[] | select(.age > 30) | .name").all(doc)
 * ```
 */
public class JsonQuery private constructor(
    /** The filter source. */
    public val source: String,
    private val program: Node,
) {
    /**
     * Runs the query against [input] and passes every output to [emit].
     *
     * @param variables values for `$name` references, e.g. `mapOf("min" to JsonNumber(3))`.
     * @param inputs values served by the `input` and `inputs` builtins.
     * @param debug receives values from `debug` and `stderr`.
     * @throws JsonQueryRuntimeException when the program raises an error.
     */
    public fun run(
        input: JsonValue,
        variables: Map<String, JsonValue> = emptyMap(),
        inputs: Iterator<JsonValue>? = null,
        debug: ((JsonValue) -> Unit)? = null,
        emit: (JsonValue) -> Unit,
    ) {
        val interpreter = Interpreter(inputs, debug)
        var env = Prelude.env(interpreter)
        for ((name, value) in variables) env = env.bindVar(name, value)
        interpreter.eval(program, Item(input, null), env) { emit(it.value) }
    }

    /** Returns every output of the query for [input]. */
    public fun all(input: JsonValue, variables: Map<String, JsonValue> = emptyMap()): List<JsonValue> {
        val out = ArrayList<JsonValue>()
        run(input, variables) { out += it }
        return out
    }

    /** Returns the first output, or `null` when the query produces nothing. Stops evaluating after the first output. */
    public fun first(input: JsonValue, variables: Map<String, JsonValue> = emptyMap()): JsonValue? {
        val limited = compileInternal("first(${source}\n)")
        var result: JsonValue? = null
        limited.run(input, variables) { result = it }
        return result
    }

    override fun toString(): String = "JsonQuery($source)"

    public companion object {
        /** Compiles [filter]. Throws [JsonQuerySyntaxException] when it is not a valid program. */
        public fun compile(filter: String): JsonQuery {
            val program = QueryParser(filter).parseProgram()
            Checker.check(program)
            return JsonQuery(filter, program)
        }

        private fun compileInternal(filter: String): JsonQuery =
            JsonQuery(filter, QueryParser(filter).parseProgram().also { Checker.check(it) })
    }
}

/** Runs a jq [filter] against this value and returns every output. */
public fun JsonValue.query(filter: String, variables: Map<String, JsonValue> = emptyMap()): List<JsonValue> =
    JsonQuery.compile(filter).all(this, variables)

/** Runs a jq [filter] against this value and returns its first output, or `null` if there is none. */
public fun JsonValue.queryFirst(filter: String, variables: Map<String, JsonValue> = emptyMap()): JsonValue? =
    JsonQuery.compile(filter).first(this, variables)
