package com.fajarnuha.kson.cli

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonBool
import com.fajarnuha.kson.JsonColors
import com.fajarnuha.kson.JsonException
import com.fajarnuha.kson.JsonFormat
import com.fajarnuha.kson.JsonNull
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonParseException
import com.fajarnuha.kson.JsonParseOptions
import com.fajarnuha.kson.JsonQuery
import com.fajarnuha.kson.JsonQueryRuntimeException
import com.fajarnuha.kson.JsonQuerySyntaxException
import com.fajarnuha.kson.JsonSchemaOptions
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.at
import com.fajarnuha.kson.deepMerge
import com.fajarnuha.kson.jsonType
import com.fajarnuha.kson.toJsonSchema
import com.fajarnuha.kson.walk

const val VERSION = "0.3.0"

/** Everything the CLI needs from the outside world, so the logic stays pure and testable. */
class CliIo(
    val readFile: (path: String) -> String,
    val readStdin: () -> String,
    /** Writes [text] to stdout exactly as given (no newline is added). */
    val out: (text: String) -> Unit,
    /** Writes one line to stderr. */
    val err: (line: String) -> Unit,
    val stdoutIsTerminal: Boolean = false,
    val stdinIsTerminal: Boolean = false,
    val getenv: (name: String) -> String? = { null },
    val environment: () -> Map<String, String> = { emptyMap() },
)

private fun CliIo.line(text: String) = out(text + "\n")

/** Signals a user error: printed to stderr, exit code 2. */
class UsageException(message: String) : Exception(message)

val USAGE = """
kson $VERSION - JSON toolkit with jq-compatible queries

Usage: kson [options] [filter] [file...]      Run a jq filter (default '.')
       kson <command> [options] [args]

  cat data.json | kson                        Pretty-print with colors
  kson '.users[] | select(.age > 30) | .name' data.json
  kson -r '.items[].id' a.json b.json

Commands:
  query <filter> [file...]   Run a jq filter (same as the default mode)
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

Reads from the given files, or from stdin when there are none or a file is '-'.
Input may hold several JSON values (e.g. newline-delimited JSON); a filter runs once per value.

Output options:
  -c, --compact-output       Compact output
      --indent <n>           Indent with n spaces (default 2)
      --tab                  Indent with tabs
  -S, --sort-keys            Sort object keys
  -a, --ascii-output         Escape all non-ASCII characters (build: -a means --array)
  -C, --color-output         Force colors
  -M, --monochrome-output    Disable colors (also: NO_COLOR=1). Colors are on when stdout is a terminal.
  -r, --raw-output           Print strings without quotes
  -j, --join-output          Like -r, without a newline after each output

Query options:
  -n, --null-input           Run the filter once with null as input (use input/inputs to read)
  -s, --slurp                Read all inputs into one array
  -R, --raw-input            Read lines of text as strings (with -s: the whole text as one string)
  -e, --exit-status          Exit 1 if the last output is false or null, 4 if there is no output
      --arg <name> <value>   Bind ${'$'}name to a string
      --argjson <name> <json> Bind ${'$'}name to a JSON value
      --strict               Reject duplicate object keys while parsing

Other options:
  -q, --quiet                validate: print nothing, only set the exit code
      --array                build: build an array instead of an object
      --title <text>         schema: set the root title
      --no-formats           schema: do not detect string formats
      --no-required          schema: do not emit 'required'
      --closed               schema: emit additionalProperties: false
  --                         Treat every following argument as positional

Query exit codes: 0 ok, 1/4 with -e, 2 usage or invalid input, 3 invalid filter, 5 runtime error.
Colors can be customized with JQ_COLORS, e.g. JQ_COLORS='0;90:0;37:0;37:0;37:0;32:1;37:1;37:34;1'.
""".trimIndent()

private val COMMANDS = setOf(
    "help", "version", "query", "q", "fmt", "format", "pretty", "min", "minify",
    "validate", "get", "keys", "type", "paths", "schema", "merge", "build",
)

private class Options {
    var compact = false
    var indent = "  "
    var sortKeys = false
    var ascii = false
    var color: Boolean? = null
    var raw = false
    var join = false
    var slurp = false
    var nullInput = false
    var rawInput = false
    var exitStatus = false
    var array = false
    var strict = false
    var quiet = false
    var title: String? = null
    var detectFormats = true
    var required = true
    var closed = false
    val named = LinkedHashMap<String, JsonValue>()
    val positional = mutableListOf<String>()

    val parseOptions get() = if (strict) JsonParseOptions.Strict else JsonParseOptions.Default
}

private const val COMBINABLE = "crjsneRCMSaqt"

private fun parseOptions(args: List<String>, command: String): Options {
    val o = Options()
    var i = 0
    var onlyPositional = false

    // Expand combined short flags such as -rc into -r -c.
    val expanded = args.flatMapIndexed { idx, a ->
        val prev = args.getOrNull(idx - 1)
        if (a.length > 2 && a[0] == '-' && a[1] != '-' && a.drop(1).all { it in COMBINABLE } && prev != "--arg" && prev != "--argjson") {
            a.drop(1).map { "-$it" }
        } else {
            listOf(a)
        }
    }

    fun value(flag: String): String = expanded.getOrNull(++i) ?: throw UsageException("Missing value for $flag")

    while (i < expanded.size) {
        val a = expanded[i]
        if (onlyPositional || a == "-" || !a.startsWith("-") || (a.length > 1 && !a[1].isLetter() && a[1] != '-')) {
            o.positional += a
            i++
            continue
        }
        when (a) {
            "--" -> onlyPositional = true
            "-c", "--compact", "--compact-output" -> o.compact = true
            "-i", "--indent" -> {
                val n = value(a).toIntOrNull()?.takeIf { it in 0..7 } ?: throw UsageException("--indent expects a number from 0 to 7")
                o.indent = " ".repeat(n)
                if (n == 0) o.compact = true
            }
            "-t", "--tab" -> o.indent = "\t"
            "-S", "--sort-keys" -> o.sortKeys = true
            "-a" -> if (command == "build") o.array = true else o.ascii = true
            "--ascii-output", "--ascii" -> o.ascii = true
            "--array" -> o.array = true
            "-C", "--color-output" -> o.color = true
            "-M", "--monochrome-output" -> o.color = false
            "-r", "--raw-output" -> o.raw = true
            "-j", "--join-output" -> {
                o.raw = true
                o.join = true
            }
            "-s", "--slurp" -> o.slurp = true
            "-n", "--null-input" -> o.nullInput = true
            "-R", "--raw-input" -> o.rawInput = true
            "-e", "--exit-status" -> o.exitStatus = true
            "--arg" -> {
                val name = value(a)
                o.named[name] = JsonString(value(a))
            }
            "--argjson" -> {
                val name = value(a)
                val text = value(a)
                o.named[name] = try {
                    Json.parse(text)
                } catch (e: JsonParseException) {
                    throw UsageException("--argjson $name: invalid JSON text: ${e.description}")
                }
            }
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

private fun useColor(o: Options, io: CliIo): Boolean =
    o.color ?: (io.stdoutIsTerminal && io.getenv("NO_COLOR").isNullOrEmpty() && io.getenv("TERM") != "dumb")

private fun format(o: Options, io: CliIo, defaultPretty: Boolean = true) = JsonFormat(
    pretty = defaultPretty && !o.compact,
    indent = o.indent,
    sortKeys = o.sortKeys,
    escapeNonAscii = o.ascii,
    colors = if (useColor(o, io)) io.getenv("JQ_COLORS")?.let { JsonColors.parse(it) } ?: JsonColors.Default else null,
)

/** Runs the CLI and returns the process exit code. */
fun runCli(args: List<String>, io: CliIo): Int {
    val command = args.firstOrNull()
    if (command == null) {
        if (io.stdinIsTerminal) {
            io.err(USAGE)
            return 2
        }
        return runGuarded(io) { query(emptyList(), io) }
    }
    val rest = args.drop(1)
    return runGuarded(io) {
        when (command) {
            "help", "-h", "--help" -> io.line(USAGE).let { 0 }
            "version", "-V", "--version" -> io.line("kson $VERSION").let { 0 }
            "query", "q" -> query(rest, io)
            "fmt", "format", "pretty" -> withInput(rest, command, io) { v, o -> io.line(v.toJson(format(o, io))); 0 }
            "min", "minify" -> withInput(rest, command, io) { v, o -> io.line(v.toJson(format(o, io, defaultPretty = false))); 0 }
            "validate" -> validate(rest, io)
            "get" -> get(rest, io)
            "keys" -> keys(rest, io)
            "type" -> type(rest, io)
            "paths" -> withInput(rest, command, io) { v, _ -> v.walk { p, _ -> if (p.isNotEmpty()) io.line(p) }; 0 }
            "schema" -> withInput(rest, command, io) { v, o ->
                val schema = v.toJsonSchema(
                    JsonSchemaOptions(
                        title = o.title,
                        detectFormats = o.detectFormats,
                        requireAllProperties = o.required,
                        additionalProperties = if (o.closed) false else null,
                        pretty = !o.compact,
                    ),
                )
                io.line(if (useColor(o, io)) Json.parse(schema).toJson(format(o, io)) else schema)
                0
            }
            "merge" -> merge(rest, io)
            "build" -> build(rest, io)
            else -> query(args, io)
        }
    }
}

private inline fun runGuarded(io: CliIo, block: () -> Int): Int = try {
    block()
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

private fun readSource(path: String?, io: CliIo): String =
    if (path == null || path == "-") io.readStdin() else io.readFile(path)

// ---------------------------------------------------------------------------
// jq-style query mode
// ---------------------------------------------------------------------------

private class Source(val name: String, read: () -> String) {
    val text: String by lazy(read)
}

private fun query(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args, "query")
    val filter = o.positional.firstOrNull() ?: "."
    val files = o.positional.drop(1)

    val program = try {
        JsonQuery.compile(filter)
    } catch (e: JsonQuerySyntaxException) {
        io.err("kson: error: ${e.description} (at offset ${e.offset} in filter)")
        io.err("kson: 1 compile error")
        return 3
    }

    // Sources are read lazily so that `kson -n '...'` never blocks on a terminal unless `input` is used.
    val sources = if (files.isEmpty()) {
        listOf(Source("<stdin>") { io.readStdin() })
    } else {
        files.map { f -> Source(if (f == "-") "<stdin>" else f) { readSource(f, io) } }
    }

    var currentSource = sources.firstOrNull()?.name ?: "<stdin>"
    val stream: Iterator<JsonValue> = if (o.rawInput) {
        if (o.slurp) {
            listOf<JsonValue>(JsonString(sources.joinToString("") { it.text })).iterator()
        } else {
            sources.asSequence().flatMap { s ->
                currentSource = s.name
                val lines = s.text.split('\n')
                (if (s.text.endsWith("\n")) lines.dropLast(1) else lines).asSequence().map { JsonString(it) }
            }.iterator()
        }
    } else {
        val values = sources.asSequence().flatMap { s ->
            currentSource = s.name
            Json.parseSequence(s.text, o.parseOptions)
        }
        if (o.slurp) {
            try {
                listOf<JsonValue>(JsonArray(values.toList())).iterator()
            } catch (e: JsonParseException) {
                io.err("kson: error (at $currentSource:${e.line}:${e.column}): ${e.description}")
                return 2
            }
        } else {
            values.iterator()
        }
    }

    val fmt = format(o, io)
    val variables = LinkedHashMap<String, JsonValue>()
    variables["ENV"] = JsonObject(io.environment().mapValuesTo(LinkedHashMap()) { JsonString(it.value) })
    variables["ARGS"] = JsonObject(linkedMapOf("positional" to JsonArray.Empty, "named" to JsonObject(LinkedHashMap(o.named))))
    variables.putAll(o.named)

    var last: JsonValue? = null
    var exit = 0
    val debug: (JsonValue) -> Unit = { io.err(it.toJson()) }

    fun runOne(input: JsonValue) {
        try {
            program.run(input, variables, stream, debug) { v ->
                last = v
                val text = if (o.raw && v is JsonString) v.value else v.toJson(fmt)
                io.out(if (o.join) text else text + "\n")
            }
        } catch (e: JsonQueryRuntimeException) {
            val v = e.value
            io.err(if (v is JsonString) "kson: error (at $currentSource): ${v.value}" else "kson: error (at $currentSource) (not a string): ${v.toJson()}")
            exit = 5
        }
    }

    try {
        if (o.nullInput) {
            runOne(JsonNull)
        } else {
            while (stream.hasNext()) runOne(stream.next())
        }
    } catch (e: JsonParseException) {
        io.err("kson: error (at $currentSource:${e.line}:${e.column}): ${e.description}")
        return 2
    }

    if (exit != 0) return exit
    if (o.exitStatus) {
        val l = last ?: return 4
        return if (l === JsonNull || l == JsonBool.False) 1 else 0
    }
    return 0
}

// ---------------------------------------------------------------------------
// Other commands
// ---------------------------------------------------------------------------

private inline fun withInput(args: List<String>, command: String, io: CliIo, block: (JsonValue, Options) -> Int): Int {
    val o = parseOptions(args, command)
    if (o.positional.size > 1) throw UsageException("Unexpected argument: ${o.positional[1]}")
    val value = Json.parse(readSource(o.positional.firstOrNull(), io), o.parseOptions)
    return block(value, o)
}

private fun validate(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args, "validate")
    val path = o.positional.firstOrNull()
    val text = readSource(path, io)
    return try {
        Json.parse(text, o.parseOptions)
        if (!o.quiet) io.line("valid")
        0
    } catch (e: JsonParseException) {
        if (!o.quiet) io.err("${path ?: "<stdin>"}:${e.line}:${e.column}: ${e.description}")
        1
    }
}

/** Splits a leading pointer argument (starts with '/' or is empty) from an optional file argument. */
private fun pointerAndFile(o: Options, pointerRequired: Boolean): Pair<String, String?> {
    val pos = o.positional
    return when {
        pos.isEmpty() -> if (pointerRequired) throw UsageException("Missing JSON Pointer argument") else "" to null
        pos.size == 1 && !pointerRequired && !pos[0].startsWith("/") && pos[0].isNotEmpty() -> "" to pos[0]
        pos.size <= 2 -> pos[0] to pos.getOrNull(1)
        else -> throw UsageException("Unexpected argument: ${pos[2]}")
    }
}

private fun resolve(args: List<String>, command: String, io: CliIo, pointerRequired: Boolean): Pair<JsonValue?, Options> {
    val o = parseOptions(args, command)
    val (pointer, file) = pointerAndFile(o, pointerRequired)
    val root = Json.parse(readSource(file, io), o.parseOptions)
    val found = root.at(pointer)
    if (found == null) io.err("kson: nothing at '$pointer'")
    return found to o
}

private fun get(args: List<String>, io: CliIo): Int {
    val (v, o) = resolve(args, "get", io, pointerRequired = true)
    v ?: return 1
    io.line(if (o.raw && v is JsonString) v.value else v.toJson(format(o, io)))
    return 0
}

private fun keys(args: List<String>, io: CliIo): Int {
    val (v, _) = resolve(args, "keys", io, pointerRequired = false)
    when (v) {
        null -> return 1
        is JsonObject -> v.keys.forEach { io.line(it) }
        is JsonArray -> v.indices.forEach { io.line(it.toString()) }
        else -> {
            io.err("kson: ${v.jsonType} has no keys")
            return 1
        }
    }
    return 0
}

private fun type(args: List<String>, io: CliIo): Int {
    val (v, _) = resolve(args, "type", io, pointerRequired = false)
    v ?: return 1
    io.line(v.jsonType)
    return 0
}

private fun merge(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args, "merge")
    if (o.positional.size < 2) throw UsageException("merge needs at least two files")
    if (o.positional.count { it == "-" } > 1) throw UsageException("stdin ('-') can only be used once")
    val merged = o.positional.map { path ->
        Json.parse(readSource(path, io), o.parseOptions) as? JsonObject
            ?: throw UsageException("$path: merge only works on JSON objects")
    }.reduce(JsonObject::deepMerge)
    io.line(merged.toJson(format(o, io)))
    return 0
}

private fun build(args: List<String>, io: CliIo): Int {
    val o = parseOptions(args, "build")
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
    io.line(result.toJson(format(o, io)))
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
