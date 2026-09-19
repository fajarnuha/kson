package com.fajarnuha.kson

/**
 * Output formatting options.
 *
 * @property pretty emit newlines and indentation.
 * @property indent the indentation unit used when [pretty] is true.
 * @property sortKeys emit object keys in lexicographic order instead of insertion order.
 * @property escapeNonAscii escape every character above U+007F as `\uXXXX`, producing pure-ASCII output.
 */
public data class JsonFormat(
    val pretty: Boolean = false,
    val indent: String = "  ",
    val sortKeys: Boolean = false,
    val escapeNonAscii: Boolean = false,
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
        when (v) {
            JsonNull -> sb.append("null")
            is JsonBool -> sb.append(v.value)
            is JsonNumber -> sb.append(v.literal)
            is JsonString -> quote(sb, v.value, f.escapeNonAscii)
            is JsonArray -> block(sb, '[', ']', v.items, f, depth) { emit(sb, it, f, depth + 1) }
            is JsonObject -> {
                val entries = if (f.sortKeys) v.fields.entries.sortedBy { it.key } else v.fields.entries
                block(sb, '{', '}', entries, f, depth) { (k, x) ->
                    quote(sb, k, f.escapeNonAscii)
                    sb.append(if (f.pretty) ": " else ":")
                    emit(sb, x, f, depth + 1)
                }
            }
        }
    }

    private inline fun <T> block(
        sb: StringBuilder,
        open: Char,
        close: Char,
        items: Collection<T>,
        f: JsonFormat,
        depth: Int,
        each: (T) -> Unit,
    ) {
        if (items.isEmpty()) {
            sb.append(open).append(close)
            return
        }
        sb.append(open)
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            if (f.pretty) newline(sb, f.indent, depth + 1)
            each(item)
        }
        if (f.pretty) newline(sb, f.indent, depth)
        sb.append(close)
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
                else -> if (c < ' ' || (escapeNonAscii && c > '\u007F')) unicodeEscape(sb, c) else sb.append(c)
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
}
