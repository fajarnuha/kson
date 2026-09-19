package com.fajarnuha.kson

/**
 * RFC 6901 JSON Pointer support.
 *
 * `""` addresses the document itself, `/a/0/b` walks object key `a`, array index `0`, key `b`.
 * `~1` and `~0` in a token decode to `/` and `~` respectively.
 */
public object JsonPointer {
    /** Splits [pointer] into decoded reference tokens. Throws [JsonException] on malformed syntax. */
    public fun tokens(pointer: String): List<String> {
        if (pointer.isEmpty()) return emptyList()
        if (pointer[0] != '/') throw JsonException("JSON Pointer must be empty or start with '/': '$pointer'")
        return pointer.substring(1).split('/').map(::unescape)
    }

    /** Builds a pointer string from raw (unescaped) tokens. */
    public fun of(vararg tokens: Any): String =
        tokens.joinToString(separator = "") { "/" + escape(it.toString()) }

    public fun escape(token: String): String = token.replace("~", "~0").replace("/", "~1")

    public fun unescape(token: String): String {
        if (token.indexOf('~') < 0) return token
        val sb = StringBuilder(token.length)
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c == '~') {
                when (token.getOrNull(i + 1)) {
                    '0' -> sb.append('~')
                    '1' -> sb.append('/')
                    else -> throw JsonException("Invalid escape '~${token.getOrNull(i + 1) ?: ""}' in JSON Pointer token '$token'")
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    internal fun arrayIndex(token: String): Int? {
        if (token.isEmpty() || token.length > 9) return null
        if (token.length > 1 && token[0] == '0') return null // no leading zeros per RFC 6901
        for (c in token) if (c !in '0'..'9') return null
        return token.toInt()
    }
}

/**
 * Resolves an RFC 6901 JSON Pointer against this value. Returns `null` when any step does not exist.
 * Throws [JsonException] only when [pointer] itself is malformed.
 */
public fun JsonValue.at(pointer: String): JsonValue? {
    var current: JsonValue = this
    for (token in JsonPointer.tokens(pointer)) {
        current = when (current) {
            is JsonObject -> current.fields[token] ?: return null
            is JsonArray -> JsonPointer.arrayIndex(token)?.let { current.items.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return current
}

/** Like [at] but throws [JsonTypeException] when the pointer does not resolve. */
public fun JsonValue.requireAt(pointer: String): JsonValue =
    at(pointer) ?: throw JsonTypeException("Nothing found at JSON Pointer '$pointer'")
