package com.fajarnuha.kson

/**
 * Parsing options.
 *
 * @property maxDepth maximum nesting of arrays/objects before parsing fails (guards against stack overflow).
 * @property allowDuplicateKeys when true (the default, matching `JSON.parse`) the last duplicate key wins;
 * when false a duplicate key is a parse error.
 */
public data class JsonParseOptions(
    val maxDepth: Int = 512,
    val allowDuplicateKeys: Boolean = true,
) {
    public companion object {
        public val Default: JsonParseOptions = JsonParseOptions()
        public val Strict: JsonParseOptions = JsonParseOptions(allowDuplicateKeys = false)
    }
}

/** Entry points for turning JSON text into [JsonValue]s. Strictly follows RFC 8259 (like `JSON.parse`). */
public object Json {
    /** Parses [text] into a [JsonValue]. Any JSON value is accepted at the top level. Throws [JsonParseException]. */
    public fun parse(text: String, options: JsonParseOptions = JsonParseOptions.Default): JsonValue =
        JsonParser(text, options).parseDocument()

    /** Parses [text], or returns `null` if it is not valid JSON. */
    public fun parseOrNull(text: String, options: JsonParseOptions = JsonParseOptions.Default): JsonValue? =
        try {
            parse(text, options)
        } catch (_: JsonParseException) {
            null
        }

    /** Parses [text] and requires the result to be an object. Throws [JsonParseException] or [JsonTypeException]. */
    public fun parseObject(text: String, options: JsonParseOptions = JsonParseOptions.Default): JsonObject =
        parse(text, options).jsonObject

    /** Parses [text] and requires the result to be an array. Throws [JsonParseException] or [JsonTypeException]. */
    public fun parseArray(text: String, options: JsonParseOptions = JsonParseOptions.Default): JsonArray =
        parse(text, options).jsonArray

    /**
     * Parses a stream of JSON values separated by optional whitespace (for example newline-delimited JSON,
     * or `{"a":1} {"a":2}`). Values are parsed lazily, so a syntax error surfaces only when it is reached.
     */
    public fun parseSequence(text: String, options: JsonParseOptions = JsonParseOptions.Default): Sequence<JsonValue> =
        sequence {
            val parser = JsonParser(text, options)
            while (true) yield(parser.nextInStream() ?: break)
        }

    /** Parses every value of a whitespace-separated JSON stream. See [parseSequence]. */
    public fun parseAll(text: String, options: JsonParseOptions = JsonParseOptions.Default): List<JsonValue> =
        parseSequence(text, options).toList()

    /** Returns true when [text] is valid JSON. */
    public fun isValid(text: String, options: JsonParseOptions = JsonParseOptions.Default): Boolean =
        parseOrNull(text, options) != null

    /** Re-serialises [text] without any insignificant whitespace. */
    public fun minify(text: String): String = parse(text).toJson()

    /** Re-serialises [text] with indentation. */
    public fun prettify(text: String, indent: String = "  "): String =
        parse(text).toJson(JsonFormat(pretty = true, indent = indent))
}

/** Parses this string as JSON. Throws [JsonParseException]. */
public fun String.parseJson(options: JsonParseOptions = JsonParseOptions.Default): JsonValue = Json.parse(this, options)

/** Parses this string as JSON, or returns `null` if it is not valid. */
public fun String.parseJsonOrNull(options: JsonParseOptions = JsonParseOptions.Default): JsonValue? =
    Json.parseOrNull(this, options)

internal class JsonParser(private val text: String, private val options: JsonParseOptions) {
    private var pos = 0
    private val n = text.length

    fun parseDocument(): JsonValue {
        if (pos < n && text[pos] == '﻿') pos++ // tolerate a leading byte-order mark
        skipWhitespace()
        if (pos >= n) throw error("Unexpected end of input, expected a JSON value")
        val value = parseValue(0)
        skipWhitespace()
        if (pos < n) throw error("Unexpected trailing character '${text[pos]}' after JSON value")
        return value
    }

    /** Returns the next value of a whitespace-separated stream, or `null` at the end of input. */
    fun nextInStream(): JsonValue? {
        if (pos == 0 && n > 0 && text[0] == '\uFEFF') pos++
        skipWhitespace()
        if (pos >= n) return null
        return parseValue(0)
    }

    private fun parseValue(depth: Int): JsonValue {
        if (pos >= n) throw error("Unexpected end of input, expected a JSON value")
        return when (val c = text[pos]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonString(parseString())
            't' -> literal("true", JsonBool.True)
            'f' -> literal("false", JsonBool.False)
            'n' -> literal("null", JsonNull)
            '-', in '0'..'9' -> parseNumber()
            else -> throw error("Unexpected character '${printable(c)}', expected a JSON value")
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (text.startsWith(word, pos)) {
            pos += word.length
            return value
        }
        throw error("Unexpected token, expected '$word'")
    }

    private fun parseObject(depth: Int): JsonObject {
        checkDepth(depth)
        pos++ // '{'
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (pos < n && text[pos] == '}') {
            pos++
            return JsonObject(fields)
        }
        while (true) {
            skipWhitespace()
            if (pos >= n) throw error("Unexpected end of input inside object")
            if (text[pos] != '"') throw error("Expected '\"' to start an object key but found '${printable(text[pos])}'")
            val keyStart = pos
            val key = parseString()
            skipWhitespace()
            if (pos >= n) throw error("Unexpected end of input, expected ':'")
            if (text[pos] != ':') throw error("Expected ':' after object key but found '${printable(text[pos])}'")
            pos++
            skipWhitespace()
            val value = parseValue(depth)
            if (!options.allowDuplicateKeys && key in fields) throw error("Duplicate object key \"$key\"", keyStart)
            fields[key] = value
            skipWhitespace()
            if (pos >= n) throw error("Unexpected end of input, expected ',' or '}'")
            when (text[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonObject(fields)
                }
                else -> throw error("Expected ',' or '}' after object value but found '${printable(text[pos])}'")
            }
        }
    }

    private fun parseArray(depth: Int): JsonArray {
        checkDepth(depth)
        pos++ // '['
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (pos < n && text[pos] == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            skipWhitespace()
            items += parseValue(depth)
            skipWhitespace()
            if (pos >= n) throw error("Unexpected end of input, expected ',' or ']'")
            when (text[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonArray(items)
                }
                else -> throw error("Expected ',' or ']' after array element but found '${printable(text[pos])}'")
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val start = pos
        // Fast path: no escapes.
        while (pos < n) {
            val c = text[pos]
            if (c == '"') {
                val s = text.substring(start, pos)
                pos++
                return s
            }
            if (c == '\\') break
            if (c < ' ') throw error("Unescaped control character U+${hex4(c.code)} in string")
            pos++
        }
        val sb = StringBuilder().append(text, start, pos)
        while (true) {
            if (pos >= n) throw error("Unterminated string")
            val c = text[pos]
            when {
                c == '"' -> {
                    pos++
                    return sb.toString()
                }
                c == '\\' -> {
                    pos++
                    if (pos >= n) throw error("Unterminated string")
                    when (val e = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= n) throw error("Incomplete unicode escape")
                            var code = 0
                            for (k in 1..4) {
                                val h = hexValue(text[pos + k])
                                if (h < 0) throw error("Invalid hex digit '${printable(text[pos + k])}' in unicode escape", pos + k)
                                code = (code shl 4) or h
                            }
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw error("Invalid escape sequence '\\${printable(e)}'")
                    }
                    pos++
                }
                c < ' ' -> throw error("Unescaped control character U+${hex4(c.code)} in string")
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun parseNumber(): JsonNumber {
        val start = pos
        if (text[pos] == '-') pos++
        if (pos >= n) throw error("Unexpected end of input in number")
        when (text[pos]) {
            '0' -> pos++
            in '1'..'9' -> while (pos < n && text[pos] in '0'..'9') pos++
            else -> throw error("Invalid number: expected a digit after '-'")
        }
        if (pos < n && text[pos] == '.') {
            pos++
            if (pos >= n || text[pos] !in '0'..'9') throw error("Invalid number: expected a digit after '.'")
            while (pos < n && text[pos] in '0'..'9') pos++
        }
        if (pos < n && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < n && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= n || text[pos] !in '0'..'9') throw error("Invalid number: expected a digit in exponent")
            while (pos < n && text[pos] in '0'..'9') pos++
        }
        return JsonNumber.unchecked(text.substring(start, pos))
    }

    private fun skipWhitespace() {
        while (pos < n) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun checkDepth(depth: Int) {
        if (depth > options.maxDepth) throw error("Nesting depth exceeds the limit of ${options.maxDepth}")
    }

    private fun error(message: String, at: Int = pos): JsonParseException {
        var line = 1
        var col = 1
        val end = minOf(at, n)
        var i = 0
        while (i < end) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
            i++
        }
        return JsonParseException(message, line, col, at)
    }

    private fun printable(c: Char): String = if (c < ' ') "\\u${hex4(c.code)}" else c.toString()

    private fun hex4(code: Int): String = code.toString(16).padStart(4, '0').uppercase()

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
