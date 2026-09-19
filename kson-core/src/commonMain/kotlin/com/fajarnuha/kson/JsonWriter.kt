package com.fajarnuha.kson

/**
 * Output formatting options.
 *
 * @property pretty emit newlines and indentation.
 * @property indent the indentation unit used when [pretty] is true.
 * @property sortKeys emit object keys in lexicographic order instead of insertion order.
 * @property escapeNonAscii escape every character above U+007F as `\uXXXX`, producing pure-ASCII output.
 * @property colors wrap tokens in ANSI color escapes for terminal display; `null` disables coloring.
 */
public data class JsonFormat(
    val pretty: Boolean = false,
    val indent: String = "  ",
    val sortKeys: Boolean = false,
    val escapeNonAscii: Boolean = false,
    val colors: JsonColors? = null,
) {
    public companion object {
        public val Compact: JsonFormat = JsonFormat(pretty = false)
        public val Pretty: JsonFormat = JsonFormat(pretty = true)
    }
}

/** Serialises [JsonValue]s to RFC 8259 text. */
public object JsonWriter {
    public fun write(value: JsonValue, format: JsonFormat = JsonFormat.Compact): String =
        StringBuilder().also { write(it, value, format) }.toString()

    public fun write(out: StringBuilder, value: JsonValue, format: JsonFormat = JsonFormat.Compact) {
        emit(out, value, format, 0)
    }

    private fun emit(sb: StringBuilder, v: JsonValue, f: JsonFormat, depth: Int) {
        val c = f.colors
        when (v) {
            JsonNull -> colored(sb, c?.nullColor) { sb.append("null") }
            is JsonBool -> colored(sb, if (v.value) c?.trueColor else c?.falseColor) { sb.append(v.value) }
            is JsonNumber -> colored(sb, c?.numberColor) { sb.append(v.literal) }
            is JsonString -> colored(sb, c?.stringColor) { quote(sb, v.value, f.escapeNonAscii) }
            is JsonArray -> block(sb, '[', ']', c?.arrayColor, v.items, f, depth) { emit(sb, it, f, depth + 1) }
            is JsonObject -> {
                val entries = if (f.sortKeys) v.fields.entries.sortedBy { it.key } else v.fields.entries
                block(sb, '{', '}', c?.objectColor, entries, f, depth) { (k, x) ->
                    colored(sb, c?.keyColor) { quote(sb, k, f.escapeNonAscii) }
                    colored(sb, c?.objectColor) { sb.append(':') }
                    if (f.pretty) sb.append(' ')
                    emit(sb, x, f, depth + 1)
                }
            }
        }
    }

    private inline fun colored(sb: StringBuilder, color: String?, body: () -> Unit) {
        if (color == null) {
            body()
            return
        }
        sb.append(ESC).append('[').append(color).append('m')
        body()
        sb.append(ESC).append("[0m")
    }

    private inline fun <T> block(
        sb: StringBuilder,
        open: Char,
        close: Char,
        color: String?,
        items: Collection<T>,
        f: JsonFormat,
        depth: Int,
        each: (T) -> Unit,
    ) {
        if (items.isEmpty()) {
            colored(sb, color) { sb.append(open).append(close) }
            return
        }
        colored(sb, color) { sb.append(open) }
        var first = true
        for (item in items) {
            if (!first) colored(sb, color) { sb.append(',') }
            first = false
            if (f.pretty) newline(sb, f.indent, depth + 1)
            each(item)
        }
        if (f.pretty) newline(sb, f.indent, depth)
        colored(sb, color) { sb.append(close) }
    }

    private fun newline(sb: StringBuilder, indent: String, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append(indent) }
    }

    /** Appends [s] as a quoted, escaped JSON string. */
    public fun quote(sb: StringBuilder, s: String, escapeNonAscii: Boolean = false) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == '\u007F' || (escapeNonAscii && c > '\u007F')) unicodeEscape(sb, c) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun unicodeEscape(sb: StringBuilder, c: Char) {
        val code = c.code
        sb.append("\\u")
        sb.append(HEX[(code shr 12) and 0xF])
        sb.append(HEX[(code shr 8) and 0xF])
        sb.append(HEX[(code shr 4) and 0xF])
        sb.append(HEX[code and 0xF])
    }

    private const val HEX = "0123456789abcdef"
    private const val ESC = '\u001B'
}

/**
 * ANSI SGR color codes (the part between `ESC[` and `m`) used when writing JSON to a terminal.
 * Defaults follow jq: dim gray `null`, green strings, bold containers and blue bold keys.
 */
public data class JsonColors(
    val nullColor: String = "0;90",
    val falseColor: String = "0;39",
    val trueColor: String = "0;39",
    val numberColor: String = "0;39",
    val stringColor: String = "0;32",
    val arrayColor: String = "1;39",
    val objectColor: String = "1;39",
    val keyColor: String = "34;1",
) {
    public companion object {
        public val Default: JsonColors = JsonColors()

        /**
         * Parses a jq-style `JQ_COLORS` value: colon-separated SGR codes for
         * null:false:true:numbers:strings:arrays:objects:object-keys. Missing entries keep their default.
         * Returns `null` when the value is malformed.
         */
        public fun parse(spec: String): JsonColors? {
            val parts = spec.split(':')
            if (parts.size > 8) return null
            if (parts.any { p -> p.any { it !in '0'..'9' && it != ';' } }) return null
            val d = Default
            fun at(i: Int, default: String) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: default
            return JsonColors(
                nullColor = at(0, d.nullColor),
                falseColor = at(1, d.falseColor),
                trueColor = at(2, d.trueColor),
                numberColor = at(3, d.numberColor),
                stringColor = at(4, d.stringColor),
                arrayColor = at(5, d.arrayColor),
                objectColor = at(6, d.objectColor),
                keyColor = at(7, d.keyColor),
            )
        }
    }
}
