package com.fajarnuha.kson.cli

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonException
import com.fajarnuha.kson.JsonFormat
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonParseException
import com.fajarnuha.kson.JsonParseOptions
import com.fajarnuha.kson.JsonSchemaOptions
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.at
import com.fajarnuha.kson.deepMerge
import com.fajarnuha.kson.jsonType
import com.fajarnuha.kson.toJsonSchema
import com.fajarnuha.kson.walk

const val VERSION = "0.1.0"

/** Everything the CLI needs from the outside world, so the logic stays pure and testable. */
class CliIo(
    val readFile: (path: String) -> String,
    val readStdin: () -> String,
    val out: (String) -> Unit,
    val err: (String) -> Unit,
)

/** Signals a user error: printed to stderr, exit code 2. */
class UsageException(message: String) : Exception(message)

val USAGE = """
kson $VERSION - JSON toolkit

Usage: kson <command> [options] [file]

Reads JSON from [file], or from stdin when the file is omitted or '-'.

Commands:
  fmt [file]                 Pretty-print JSON
  min [file]                 Minify JSON
  validate [file]            Check that input is valid JSON (exit 1 if not)
  get <pointer> [file]       Print the value at an RFC 6901 JSON Pointer, e.g. /users/0/name
  keys [pointer] [file]      List the keys of an object (or the indices of an array)
  type [pointer] [file]      Print the JSON type of a value
  paths [file]               List every JSON Pointer in the document
  schema [file]              Infer a JSON Schema (draft 2020-12)
  merge <file> <file>...     Deep-merge objects left to right
  build <field>...           Build JSON from arguments:
                               key=value   string field      (a.b=x nests objects)
                               key:=json   raw JSON field    (n:=1, ok:=true, l:='[1,2]')
                             With -a, each argument is an array item: 'text' or ':=json'
  help                       Show this help
  version                    Show the version

Output options (fmt, get, merge, build, schema):
  -c, --compact              Compact output
  -i, --indent <n>           Indent with n spaces (default 2)
  -t, --tab                  Indent with tabs
  -s, --sort-keys            Sort object keys
  -A, --ascii                Escape all non-ASCII characters

Other options:
  -r, --raw                  get: print strings without quotes
  -a, --array                build: build an array instead of an object
  --strict                   Reject duplicate object keys while parsing
  --title <text>             schema: set the root title
  --no-formats               schema: do not detect string formats
  --no-required              schema: do not emit 'required'
  --closed                   schema: emit additionalProperties: false
  -q, --quiet                validate: print nothing, only set the exit code
  --                         Treat every following argument as positional (e.g. build -a -- -x)
""".trimIndent()

private class Options {
    var compact = false
    var indent = "  "
    var sortKeys = false
    var ascii = false
    var raw = false
    var array = false
    var strict = false
    var quiet = false
    var title: String? = null
    var detectFormats = true
    var required = true
    var closed = false
    val positional = mutableListOf<String>()

    val parseOptions get() = if (strict) JsonParseOptions.Strict else JsonParseOptions.Default

    fun format(defaultPretty: Boolean = true) =
        JsonFormat(pretty = defaultPretty && !compact, indent = indent, sortKeys = sortKeys, escapeNonAscii = ascii)
}

private fun parseOptions(args: List<String>): Options {
    val o = Options()
    var i = 0
    var onlyPositional = false
    fun value(flag: String): String = args.getOrNull(++i) ?: throw UsageException("Missing value for $flag")
    while (i < args.size) {
        val a = args[i]
        if (onlyPositional || a == "-" || !a.startsWith("-")) {
            o.positional += a
        } else when (a) {
            "--" -> onlyPositional = true
            "-c", "--compact" -> o.compact = true
            "-i", "--indent" -> {
                val n = value(a).toIntOrNull()?.takeIf { it in 0..16 } ?: throw UsageException("--indent expects a number from 0 to 16")
                o.indent = " ".repeat(n)
            }
            "-t", "--tab" -> o.indent = "\t"
            "-s", "--sort-keys" -> o.sortKeys = true
            "-A", "--ascii" -> o.ascii = true
            "-r", "--raw" -> o.raw = true
            "-a", "--array" -> o.array = true
            "-q", "--quiet" -> o.quiet = true
            "--strict" -> o.strict = true
            "--title" -> o.title = value(a)
            "--no-formats" -> o.detectFormats = false
            "--no-required" -> o.required = false
            "--closed" -> o.closed = true
            else -> throw UsageException("Unknown option: $a")
        }
        i++
    }
    return o
}

/** Runs the CLI and returns the process exit code: 0 success, 1 invalid JSON / lookup miss, 2 usage error. */
fun runCli(args: List<String>, io: CliIo): Int {
    val command = args.firstOrNull() ?: run {
        io.err(USAGE)
        return 2
    }
    val rest = args.drop(1)
    return try {
        when (command) {
            "help", "-h", "--help" -> io.out(USAGE).let { 0 }
            "version", "-v", "--version" -> io.out("kson $VERSION").let { 0 }
            "fmt", "format", "pretty" -> withInput(rest, io, maxPositional = 1) { v, o -> io.out(v.toJson(o.format())); 0 }
            "min", "minify" -> withInput(rest, io, maxPositional = 1) { v, o -> io.out(v.toJson(o.format(defaultPretty = false))); 0 }
            "validate" -> validate(rest, io)
            "get" -> get(rest, io)
            "keys" -> keys(rest, io)
            "type" -> type(rest, io)
            "paths" -> withInput(rest, io, maxPositional = 1) { v, _ -> v.walk { p, _ -> if (p.isNotEmpty()) io.out(p) }; 0 }
            "schema" -> withInput(rest, io, maxPositional = 1) { v, o ->
                io.out(
                    v.toJsonSchema(
                        JsonSchemaOptions(
                            title = o.title,
                            detectFormats = o.detectFormats,
                            requireAllProperties = o.required,
                            additionalProperties = if (o.closed) false else null,
                            pretty = !o.compact,
                        ),
                    ),
                )
                0
            }
            "merge" -> merge(rest, io)
            "build" -> build(rest, io)
            else -> throw UsageException("Unknown command: $command. Run 'kson help' for usage.")
        }
    } catch (e: UsageException) {
        io.err("kson: ${e.message}")
        2
    } catch (e: JsonParseException) {
        io.err("kson: invalid JSON: ${e.message}")
        1
    } catch (e: JsonException) {
        io.err("kson: ${e.message}")
        1
    }
}

private fun readSource(path: String?, io: CliIo): String =
    if (path == null || path == "-") io.readStdin() else io.readFile(path)

private inline fun withInput(args: List<String>, io: CliIo, maxPositional: Int, block: (JsonValue, Options) -> Int): Int {
    val o = parseOptions(args)
    if (o.positional.size > maxPositional) throw UsageException("Unexpected argument: ${o.positional[maxPositional]}")
    val value = Json.parse(readSource(o.positional.firstOrNull(), io), o.parseOptions)
    return block(value, o)
}

private fun validate(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args)
    val path = o.positional.firstOrNull()
    val text = readSource(path, io)
    return try {
        Json.parse(text, o.parseOptions)
        if (!o.quiet) io.out("valid")
        0
    } catch (e: JsonParseException) {
        if (!o.quiet) io.err("${path ?: "<stdin>"}:${e.line}:${e.column}: ${e.description}")
        1
    }
}

/** Splits leading pointer argument (starts with '/' or is empty) from an optional file argument. */
private fun pointerAndFile(o: Options, pointerRequired: Boolean): Pair<String, String?> {
    val pos = o.positional
    return when {
        pos.isEmpty() -> if (pointerRequired) throw UsageException("Missing JSON Pointer argument") else "" to null
        pos.size == 1 && !pointerRequired && !pos[0].startsWith("/") && pos[0].isNotEmpty() -> "" to pos[0]
        pos.size <= 2 -> pos[0] to pos.getOrNull(1)
        else -> throw UsageException("Unexpected argument: ${pos[2]}")
    }
}

private fun resolve(args: List<String>, io: CliIo, pointerRequired: Boolean): Pair<JsonValue?, Options> {
    val o = parseOptions(args)
    val (pointer, file) = pointerAndFile(o, pointerRequired)
    val root = Json.parse(readSource(file, io), o.parseOptions)
    val found = root.at(pointer)
    if (found == null) io.err("kson: nothing at '$pointer'")
    return found to o
}

private fun get(args: List<String>, io: CliIo): Int {
    val (v, o) = resolve(args, io, pointerRequired = true)
    v ?: return 1
    io.out(if (o.raw && v is JsonString) v.value else v.toJson(o.format()))
    return 0
}

private fun keys(args: List<String>, io: CliIo): Int {
    val (v, _) = resolve(args, io, pointerRequired = false)
    when (v) {
        null -> return 1
        is JsonObject -> v.keys.forEach(io.out)
        is JsonArray -> v.indices.forEach { io.out(it.toString()) }
        else -> {
            io.err("kson: ${v.jsonType} has no keys")
            return 1
        }
    }
    return 0
}

private fun type(args: List<String>, io: CliIo): Int {
    val (v, _) = resolve(args, io, pointerRequired = false)
    v ?: return 1
    io.out(v.jsonType)
    return 0
}

private fun merge(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args)
    if (o.positional.size < 2) throw UsageException("merge needs at least two files")
    if (o.positional.count { it == "-" } > 1) throw UsageException("stdin ('-') can only be used once")
    val merged = o.positional.map { path ->
        Json.parse(readSource(path, io), o.parseOptions) as? JsonObject
            ?: throw UsageException("$path: merge only works on JSON objects")
    }.reduce(JsonObject::deepMerge)
    io.out(merged.toJson(o.format()))
    return 0
}

private fun build(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args)
    val result: JsonValue = if (o.array) {
        JsonArray(o.positional.map { if (it.startsWith(":=")) parseRaw(it.substring(2), it, o) else JsonString(it) })
    } else {
        var obj = JsonObject.Empty
        for (field in o.positional) {
            val raw = field.indexOf(":=")
            val eq = field.indexOf('=')
            if (eq <= 0) throw UsageException("Expected key=value or key:=json but got '$field'")
            val (key, value) = if (raw > 0 && raw < eq) {
                field.substring(0, raw) to parseRaw(field.substring(raw + 2), field, o)
            } else {
                field.substring(0, eq) to JsonString(field.substring(eq + 1))
            }
            obj = obj.deepMerge(nest(splitPath(key), value))
        }
        obj
    }
    io.out(result.toJson(o.format()))
    return 0
}

private fun parseRaw(text: String, field: String, o: Options): JsonValue = try {
    Json.parse(text, o.parseOptions)
} catch (e: JsonParseException) {
    throw UsageException("'$field': value after ':=' is not valid JSON (${e.message}). Use key=value for strings.")
}

/** Splits `a.b\.c` into `["a", "b.c"]`. */
private fun splitPath(key: String): List<String> {
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < key.length) {
        val c = key[i]
        when {
            c == '\\' && i + 1 < key.length && key[i + 1] == '.' -> {
                sb.append('.')
                i++
            }
            c == '.' -> {
                parts += sb.toString()
                sb.clear()
            }
            else -> sb.append(c)
        }
        i++
    }
    parts += sb.toString()
    if (parts.any { it.isEmpty() }) throw UsageException("Empty key segment in '$key'")
    return parts
}

private fun nest(path: List<String>, value: JsonValue): JsonObject =
    path.foldRight(value) { key, acc -> JsonObject(mapOf(key to acc)) } as JsonObject

