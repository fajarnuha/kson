package com.fajarnuha.kson.query

import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonBool
import com.fajarnuha.kson.JsonNull
import com.fajarnuha.kson.JsonNumber
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonQueryRuntimeException
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.deepMerge
import com.fajarnuha.kson.jsonType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal fun fail(message: String): Nothing = throw JsonQueryRuntimeException(JsonString(message))

internal fun truthy(v: JsonValue): Boolean = !(v === JsonNull || (v is JsonBool && !v.value))

/** `type (value)` the way jq prints values in error messages, truncated. */
internal fun describe(v: JsonValue): String {
    val text = v.toJson()
    val shown = if (text.length > 30) text.take(27) + "..." else text
    return "${v.jsonType} ($shown)"
}

// ---------------------------------------------------------------------------
// Numbers
// ---------------------------------------------------------------------------

/** Converts a computed double to a JSON number, formatted the way jq prints doubles (`1e-07`, `1e+17`, `0.5`). */
internal fun num(d: Double): JsonValue {
    if (d.isNaN()) return JsonNull
    val x = when (d) {
        Double.POSITIVE_INFINITY -> Double.MAX_VALUE
        Double.NEGATIVE_INFINITY -> -Double.MAX_VALUE
        else -> d
    }
    return JsonNumber.parse(formatDouble(x))
}

/**
 * Shortest round-trip digits laid out like jq's `jvp_dtoa_fmt`: plain notation unless the decimal exponent is
 * below -4 or more than 15 places beyond the significant digits, in which case `d.ddde±XX` is used.
 */
internal fun formatDouble(d: Double): String {
    if (d == 0.0) return if (1.0 / d < 0) "-0" else "0"
    val text = abs(d).toString()
    val ePos = text.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (ePos >= 0) text.substring(0, ePos) else text
    val exp = if (ePos >= 0) text.substring(ePos + 1).toInt() else 0
    val dot = mantissa.indexOf('.')
    val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
    val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""
    var digits = intPart + fracPart
    var decpt = intPart.length + exp
    val lead = digits.indexOfFirst { it != '0' }
    digits = digits.substring(lead)
    decpt -= lead
    digits = digits.trimEnd('0')
    val sign = if (d < 0) "-" else ""
    val nd = digits.length
    val body = when {
        decpt <= -4 || decpt > nd + 15 -> {
            val e = decpt - 1
            val expText = abs(e).toString().padStart(2, '0')
            digits[0] + (if (nd > 1) "." + digits.substring(1) else "") + "e" + (if (e < 0) "-" else "+") + expText
        }
        decpt <= 0 -> "0." + "0".repeat(-decpt) + digits
        decpt >= nd -> digits + "0".repeat(decpt - nd)
        else -> digits.substring(0, decpt) + "." + digits.substring(decpt)
    }
    return sign + body
}

/** Parses number text the way `tonumber` does: surrounding whitespace allowed, `.5` and `1.` accepted. */
internal fun parseNumberText(s: String): JsonValue? {
    val t = s.trim()
    if (t.isEmpty()) return null
    if (t == "nan" || t == "NaN") return JsonNull
    JsonNumber.parseOrNull(t)?.let { return it }
    if (t.any { it !in '0'..'9' && it != '.' && it != '-' && it != '+' && it != 'e' && it != 'E' }) return null
    return t.toDoubleOrNull()?.let { num(it) }
}

internal fun num(l: Long): JsonValue = JsonNumber(l)

private fun addNumbers(a: JsonNumber, b: JsonNumber): JsonValue {
    val x = a.toLongOrNull()
    val y = b.toLongOrNull()
    if (x != null && y != null) {
        val r = x + y
        if (((x xor r) and (y xor r)) >= 0) return num(r)
    }
    return num(a.toDouble() + b.toDouble())
}

private fun subNumbers(a: JsonNumber, b: JsonNumber): JsonValue {
    val x = a.toLongOrNull()
    val y = b.toLongOrNull()
    if (x != null && y != null) {
        val r = x - y
        if (((x xor y) and (x xor r)) >= 0) return num(r)
    }
    return num(a.toDouble() - b.toDouble())
}

private fun mulNumbers(a: JsonNumber, b: JsonNumber): JsonValue {
    val x = a.toLongOrNull()
    val y = b.toLongOrNull()
    if (x != null && y != null && abs(x) < 3_037_000_499L && abs(y) < 3_037_000_499L) return num(x * y)
    return num(a.toDouble() * b.toDouble())
}

// ---------------------------------------------------------------------------
// Operators
// ---------------------------------------------------------------------------

internal fun binop(op: String, a: JsonValue, b: JsonValue): JsonValue = when (op) {
    "+" -> add(a, b)
    "-" -> subtract(a, b)
    "*" -> multiply(a, b)
    "/" -> divide(a, b)
    "%" -> modulo(a, b)
    "==" -> JsonBool.of(a == b)
    "!=" -> JsonBool.of(a != b)
    "<" -> JsonBool.of(compareJson(a, b) < 0)
    "<=" -> JsonBool.of(compareJson(a, b) <= 0)
    ">" -> JsonBool.of(compareJson(a, b) > 0)
    ">=" -> JsonBool.of(compareJson(a, b) >= 0)
    else -> fail("Unknown operator $op")
}

internal fun add(a: JsonValue, b: JsonValue): JsonValue = when {
    a === JsonNull -> b
    b === JsonNull -> a
    a is JsonNumber && b is JsonNumber -> addNumbers(a, b)
    a is JsonString && b is JsonString -> JsonString(a.value + b.value)
    a is JsonArray && b is JsonArray -> JsonArray(a.items + b.items)
    a is JsonObject && b is JsonObject -> JsonObject(LinkedHashMap(a.fields).apply { putAll(b.fields) })
    else -> fail("${describe(a)} and ${describe(b)} cannot be added")
}

private fun subtract(a: JsonValue, b: JsonValue): JsonValue = when {
    a is JsonNumber && b is JsonNumber -> subNumbers(a, b)
    a is JsonArray && b is JsonArray -> JsonArray(a.items.filter { it !in b.items })
    else -> fail("${describe(a)} and ${describe(b)} cannot be subtracted")
}

private fun multiply(a: JsonValue, b: JsonValue): JsonValue = when {
    a is JsonNumber && b is JsonNumber -> mulNumbers(a, b)
    a is JsonString && b is JsonNumber -> repeat(a, b)
    a is JsonNumber && b is JsonString -> repeat(b, a)
    a is JsonObject && b is JsonObject -> a.deepMerge(b)
    else -> fail("${describe(a)} and ${describe(b)} cannot be multiplied")
}

private fun repeat(s: JsonString, n: JsonNumber): JsonValue {
    val times = n.toDouble()
    if (times < 0.0) return JsonNull
    val count = times.toInt()
    if (count.toLong() * s.value.length > 100_000_000L) fail("Repeat string result too long")
    return JsonString(s.value.repeat(count))
}

private fun divide(a: JsonValue, b: JsonValue): JsonValue = when {
    a is JsonNumber && b is JsonNumber -> {
        if (b.toDouble() == 0.0) fail("${describe(a)} and ${describe(b)} cannot be divided because the divisor is zero")
        val x = a.toLongOrNull()
        val y = b.toLongOrNull()
        if (x != null && y != null && y != 0L && x % y == 0L && !(x == Long.MIN_VALUE && y == -1L)) num(x / y) else num(a.toDouble() / b.toDouble())
    }
    a is JsonString && b is JsonString -> splitString(a.value, b.value)
    else -> fail("${describe(a)} and ${describe(b)} cannot be divided")
}

private fun modulo(a: JsonValue, b: JsonValue): JsonValue {
    if (a !is JsonNumber || b !is JsonNumber) fail("${describe(a)} and ${describe(b)} cannot be divided")
    val x = truncateToLong(a.toDouble())
    val y = truncateToLong(b.toDouble())
    if (y == 0L) fail("${describe(a)} and ${describe(b)} cannot be divided because the divisor is zero")
    if (y == -1L) return num(0L)
    return num(x % y)
}

private fun truncateToLong(d: Double): Long = when {
    d.isNaN() -> 0L
    d >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
    d <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
    else -> d.toLong()
}

/** Negates by flipping the sign of the literal, so precision and `-0` are preserved. */
internal fun negate(v: JsonValue): JsonValue {
    if (v !is JsonNumber) fail("${describe(v)} cannot be negated")
    val lit = v.literal
    return JsonNumber.parse(if (lit.startsWith("-")) lit.substring(1) else "-$lit")
}

internal fun splitString(s: String, sep: String): JsonValue {
    if (s.isEmpty()) return JsonArray.Empty
    if (sep.isEmpty()) return JsonArray(codePoints(s).map { JsonString(fromCodePoints(listOf(it))) })
    return JsonArray(s.split(sep).map { JsonString(it) })
}

// ---------------------------------------------------------------------------
// Ordering: null < false < true < numbers < strings < arrays < objects
// ---------------------------------------------------------------------------

private fun rank(v: JsonValue): Int = when (v) {
    JsonNull -> 0
    is JsonBool -> if (v.value) 2 else 1
    is JsonNumber -> 3
    is JsonString -> 4
    is JsonArray -> 5
    is JsonObject -> 6
}

internal fun compareJson(a: JsonValue, b: JsonValue): Int {
    val ra = rank(a)
    val rb = rank(b)
    if (ra != rb) return ra.compareTo(rb)
    return when (a) {
        is JsonNumber -> a.compareTo(b as JsonNumber)
        is JsonString -> compareStrings(a.value, (b as JsonString).value)
        is JsonArray -> {
            val bb = b as JsonArray
            for (i in 0 until minOf(a.size, bb.size)) {
                val c = compareJson(a[i], bb[i])
                if (c != 0) return c
            }
            a.size.compareTo(bb.size)
        }
        is JsonObject -> {
            val bb = b as JsonObject
            val ka = a.keys.sortedWith(::compareStrings)
            val kb = bb.keys.sortedWith(::compareStrings)
            for (i in 0 until minOf(ka.size, kb.size)) {
                val c = compareStrings(ka[i], kb[i])
                if (c != 0) return c
            }
            if (ka.size != kb.size) return ka.size.compareTo(kb.size)
            for (k in ka) {
                val c = compareJson(a.getValue(k), bb.getValue(k))
                if (c != 0) return c
            }
            0
        }
        else -> 0
    }
}

/** Compares by Unicode code point (which matches jq's byte-wise UTF-8 ordering). */
internal fun compareStrings(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val x = a[i]
        val y = b[i]
        if (x != y) {
            // Surrogates (U+D800..U+DFFF) encode code points above U+FFFF, which must sort after U+E000..U+FFFF.
            val xs = x.isSurrogate()
            val ys = y.isSurrogate()
            if (xs != ys) return if (xs) 1 else -1
            return x.compareTo(y)
        }
    }
    return a.length.compareTo(b.length)
}

internal val JsonComparator: Comparator<JsonValue> = Comparator { a, b -> compareJson(a, b) }

// ---------------------------------------------------------------------------
// Indexing and paths
// ---------------------------------------------------------------------------

internal fun indexValue(v: JsonValue, k: JsonValue): JsonValue = when {
    v is JsonObject && k is JsonString -> v.fields[k.value] ?: JsonNull
    v is JsonArray && k is JsonNumber -> {
        var i = floor(k.toDouble())
        if (i < 0) i += v.size
        if (i < 0 || i >= v.size) JsonNull else v[i.toInt()]
    }
    v === JsonNull && (k is JsonString || k is JsonNumber || k is JsonObject) -> JsonNull
    v is JsonArray && k is JsonArray -> indicesOf(v, k)
    (v is JsonArray || v is JsonString) && k is JsonObject -> sliceValue(v, k["start"] ?: JsonNull, k["end"] ?: JsonNull)
    k is JsonString -> fail("Cannot index ${v.jsonType} with \"${k.value}\"")
    else -> fail("Cannot index ${v.jsonType} with ${k.jsonType}")
}

private fun sliceBounds(len: Int, from: JsonValue, to: JsonValue): Pair<Int, Int> {
    if ((from !is JsonNumber && from !== JsonNull) || (to !is JsonNumber && to !== JsonNull)) {
        fail("Start and end indices of an array slice must be numbers")
    }
    fun norm(v: JsonValue, default: Int, round: (Double) -> Double): Int {
        if (v !is JsonNumber) return default
        var d = round(v.toDouble())
        if (d < 0) d += len
        return d.coerceIn(0.0, len.toDouble()).toInt()
    }
    val start = norm(from, 0, ::floor)
    val end = maxOf(start, norm(to, len, ::ceil))
    return start to end
}

internal fun sliceValue(v: JsonValue, from: JsonValue, to: JsonValue): JsonValue = when (v) {
    JsonNull -> JsonNull
    is JsonArray -> {
        val (s, e) = sliceBounds(v.size, from, to)
        JsonArray(v.items.subList(s, e).toList())
    }
    is JsonString -> {
        val cps = codePoints(v.value)
        val (s, e) = sliceBounds(cps.size, from, to)
        JsonString(fromCodePoints(cps.subList(s, e)))
    }
    else -> fail("Cannot index ${v.jsonType} with object")
}

internal fun sliceKey(from: JsonValue, to: JsonValue): JsonObject =
    JsonObject(linkedMapOf("start" to from, "end" to to))

internal fun getPath(v: JsonValue, path: List<JsonValue>): JsonValue {
    var cur = v
    for (k in path) {
        if (cur === JsonNull) return JsonNull
        cur = indexValue(cur, k)
    }
    return cur
}

internal fun setPath(cur: JsonValue, path: List<JsonValue>, i: Int, value: JsonValue): JsonValue {
    if (i == path.size) return value
    val k = path[i]
    return when (k) {
        is JsonString -> {
            val obj = when (cur) {
                JsonNull -> JsonObject.Empty
                is JsonObject -> cur
                else -> fail("Cannot index ${cur.jsonType} with \"${k.value}\"")
            }
            val child = obj.fields[k.value] ?: JsonNull
            JsonObject(LinkedHashMap(obj.fields).apply { put(k.value, setPath(child, path, i + 1, value)) })
        }
        is JsonNumber -> {
            val arr = when (cur) {
                JsonNull -> JsonArray.Empty
                is JsonArray -> cur
                else -> fail("Cannot index ${cur.jsonType} with number")
            }
            var idx = floor(k.toDouble())
            if (idx < 0) {
                idx += arr.size
                if (idx < 0) fail("Out of bounds negative array index")
            }
            if (idx > 100_000_000) fail("Array index too large")
            val list = ArrayList(arr.items)
            val at = idx.toInt()
            while (list.size <= at) list.add(JsonNull)
            list[at] = setPath(list[at], path, i + 1, value)
            JsonArray(list)
        }
        is JsonObject -> {
            val arr = when (cur) {
                JsonNull -> JsonArray.Empty
                is JsonArray -> cur
                else -> fail("Cannot update field at object index of ${cur.jsonType}")
            }
            val (s, e) = sliceBounds(arr.size, k["start"] ?: JsonNull, k["end"] ?: JsonNull)
            val replaced = setPath(JsonArray(arr.items.subList(s, e).toList()), path, i + 1, value)
            if (replaced !is JsonArray) fail("A slice of an array can only be assigned another array")
            JsonArray(arr.items.subList(0, s) + replaced.items + arr.items.subList(e, arr.size))
        }
        else -> fail("Invalid path component ${describe(k)}")
    }
}

internal fun deletePaths(v: JsonValue, paths: List<List<JsonValue>>): JsonValue {
    val sorted = paths.sortedWith { a, b -> compareJson(JsonArray(b), JsonArray(a)) }
    var cur = v
    for (p in sorted) cur = deletePath(cur, p, 0)
    return cur
}

private fun deletePath(cur: JsonValue, path: List<JsonValue>, i: Int): JsonValue {
    if (path.isEmpty()) return JsonNull
    if (cur === JsonNull) return cur
    val k = path[i]
    if (i == path.size - 1) {
        return when {
            cur is JsonObject && k is JsonString -> JsonObject(LinkedHashMap(cur.fields).apply { remove(k.value) })
            cur is JsonArray && k is JsonNumber -> {
                var idx = floor(k.toDouble())
                if (idx < 0) idx += cur.size
                if (idx < 0 || idx >= cur.size) cur else JsonArray(cur.items.filterIndexed { j, _ -> j != idx.toInt() })
            }
            cur is JsonArray && k is JsonObject -> {
                val (s, e) = sliceBounds(cur.size, k["start"] ?: JsonNull, k["end"] ?: JsonNull)
                JsonArray(cur.items.subList(0, s) + cur.items.subList(e, cur.size))
            }
            k is JsonString -> fail("Cannot delete field at object index of ${cur.jsonType}")
            else -> fail("Cannot delete field at index of ${cur.jsonType}")
        }
    }
    val child = indexValue(cur, k)
    if (child === JsonNull) return cur
    val updated = deletePath(child, path, i + 1)
    return when {
        cur is JsonObject && k is JsonString -> JsonObject(LinkedHashMap(cur.fields).apply { put(k.value, updated) })
        cur is JsonArray && k is JsonNumber -> {
            var idx = floor(k.toDouble())
            if (idx < 0) idx += cur.size
            JsonArray(cur.items.toMutableList().also { it[idx.toInt()] = updated })
        }
        cur is JsonArray && k is JsonObject -> {
            val (s, e) = sliceBounds(cur.size, k["start"] ?: JsonNull, k["end"] ?: JsonNull)
            val arr = updated as? JsonArray ?: fail("A slice of an array can only be assigned another array")
            JsonArray(cur.items.subList(0, s) + arr.items + cur.items.subList(e, cur.size))
        }
        else -> cur
    }
}

// ---------------------------------------------------------------------------
// Misc helpers
// ---------------------------------------------------------------------------

internal fun indicesOf(v: JsonValue, sub: JsonValue): JsonValue = when {
    v === JsonNull -> JsonNull
    v is JsonString && sub is JsonString -> {
        if (sub.value.isEmpty()) {
            JsonArray.Empty
        } else {
            val out = ArrayList<JsonValue>()
            var from = v.value.indexOf(sub.value)
            while (from >= 0) {
                out += num(from.toLong())
                from = v.value.indexOf(sub.value, from + 1)
            }
            JsonArray(out)
        }
    }
    v is JsonArray && sub is JsonArray -> {
        if (sub.isEmpty()) {
            JsonNull
        } else {
            val out = ArrayList<JsonValue>()
            for (i in 0..v.size - sub.size) {
                if ((sub.indices).all { j -> v[i + j] == sub[j] }) out += num(i.toLong())
            }
            JsonArray(out)
        }
    }
    v is JsonArray -> JsonArray(v.items.indices.filter { v[it] == sub }.map { num(it.toLong()) })
    else -> fail("Cannot determine indices of ${describe(sub)} in ${describe(v)}")
}

internal fun containsJson(a: JsonValue, b: JsonValue): Boolean {
    if (a.jsonType != b.jsonType) fail("${describe(a)} and ${describe(b)} cannot have their containment checked")
    return containsInner(a, b)
}

private fun containsInner(a: JsonValue, b: JsonValue): Boolean = when {
    a is JsonObject && b is JsonObject -> b.fields.all { (k, bv) -> a.fields[k]?.let { containsInner(it, bv) } ?: false }
    a is JsonArray && b is JsonArray -> b.items.all { bv -> a.items.any { containsInner(it, bv) } }
    a is JsonString && b is JsonString -> a.value.contains(b.value)
    else -> a == b
}

internal fun lengthOf(v: JsonValue): JsonValue = when (v) {
    JsonNull -> num(0L)
    is JsonBool -> fail("${describe(v)} has no length")
    is JsonNumber -> if (v.toDouble() < 0) negate(v) else v
    is JsonString -> num(codePoints(v.value).size.toLong())
    is JsonArray -> num(v.size.toLong())
    is JsonObject -> num(v.size.toLong())
}

internal fun toStringValue(v: JsonValue): String = if (v is JsonString) v.value else v.toJson()

internal fun codePoints(s: String): List<Int> {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            out += 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
            i += 2
        } else {
            out += c.code
            i++
        }
    }
    return out
}

internal fun fromCodePoints(cps: List<Int>): String {
    val sb = StringBuilder(cps.size)
    for (cp in cps) {
        if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
            sb.append('\uFFFD')
        } else if (cp >= 0x10000) {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        } else {
            sb.append(cp.toChar())
        }
    }
    return sb.toString()
}

internal fun flatten(v: JsonValue, depth: Double): JsonValue {
    if (v !is JsonArray) fail("Cannot flatten ${describe(v)}")
    if (depth < 0) fail("flatten depth must not be negative")
    val out = ArrayList<JsonValue>()
    fun go(a: JsonArray, d: Double) {
        for (x in a.items) if (x is JsonArray && d > 0) go(x, d - 1) else out += x
    }
    go(v, depth)
    return JsonArray(out)
}

// ---------------------------------------------------------------------------
// @formats
// ---------------------------------------------------------------------------

internal val FORMATS = setOf("text", "json", "html", "uri", "csv", "tsv", "sh", "base64", "base64d")

internal fun applyFormat(name: String, v: JsonValue): String = when (name) {
    "text" -> toStringValue(v)
    "json" -> v.toJson()
    "html" -> buildString {
        for (c in toStringValue(v)) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '\'' -> append("&apos;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
    "uri" -> buildString {
        for (b in toStringValue(v).encodeToByteArray()) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%').append(HEX_UPPER[c shr 4]).append(HEX_UPPER[c and 0xF])
            }
        }
    }
    "csv" -> {
        if (v !is JsonArray) fail("${describe(v)} cannot be csv-formatted, only an array can be")
        v.items.joinToString(",") { x ->
            when (x) {
                is JsonNumber -> x.literal
                is JsonString -> "\"" + x.value.replace("\"", "\"\"") + "\""
                is JsonBool -> x.value.toString()
                JsonNull -> ""
                else -> fail("${describe(x)} is not valid in a csv row")
            }
        }
    }
    "tsv" -> {
        if (v !is JsonArray) fail("${describe(v)} cannot be tsv-formatted, only an array can be")
        v.items.joinToString("\t") { x ->
            when (x) {
                is JsonNumber -> x.literal
                is JsonString -> x.value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
                is JsonBool -> x.value.toString()
                JsonNull -> ""
                else -> fail("${describe(x)} is not valid in a tsv row")
            }
        }
    }
    "sh" -> {
        fun quote(x: JsonValue): String = when (x) {
            is JsonString -> "'" + x.value.replace("'", "'\\''") + "'"
            is JsonArray, is JsonObject -> fail("${describe(x)} can not be escaped for shell")
            else -> x.toJson()
        }
        if (v is JsonArray) v.items.joinToString(" ") { quote(it) } else quote(v)
    }
    "base64" -> Base64Codec.encode(toStringValue(v).encodeToByteArray())
    "base64d" -> {
        val s = toStringValue(v)
        val bytes = Base64Codec.decode(s) ?: fail("${describe(v)} is not valid base64 data")
        bytes.decodeToString()
    }
    else -> fail("$name is not a valid format")
}

private const val HEX_UPPER = "0123456789ABCDEF"

internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            sb.append(if (b1 < 0) '=' else ALPHABET[((b1 and 0xF) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            sb.append(if (b2 < 0) '=' else ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    /** Decodes standard base64; padding is optional. Returns null on invalid input. */
    fun decode(s: String): ByteArray? {
        val clean = s.trimEnd('=')
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        if (clean.length % 4 == 1) return null
        return out.toByteArray()
    }
}

// ---------------------------------------------------------------------------
// Regular expressions (Oniguruma-style flags mapped onto Kotlin Regex)
// ---------------------------------------------------------------------------

internal class CompiledRegex(val regex: Regex, val global: Boolean, val skipEmpty: Boolean, val groupNames: List<String?>)

internal fun compileRegex(re: JsonValue, flags: JsonValue, regexCache: MutableMap<String, CompiledRegex>): CompiledRegex {
    if (re !is JsonString) fail("${describe(re)} cannot be matched, as it is not a string")
    if (flags !is JsonString && flags !== JsonNull) fail("${describe(flags)} is not a string")
    val flagText = (flags as? JsonString)?.value ?: ""
    val cacheKey = flagText + "\u0000" + re.value
    regexCache[cacheKey]?.let { return it }
    var global = false
    var skipEmpty = false
    var prefix = ""
    val options = HashSet<RegexOption>()
    for (c in flagText) {
        when (c) {
            'g' -> global = true
            'i' -> options += RegexOption.IGNORE_CASE
            'x' -> prefix += "(?x)"
            's' -> prefix += "(?s)"
            'n' -> skipEmpty = true
            'p' -> prefix += "(?s)"
            'l' -> {}
            else -> fail("$flagText is not a valid modifier string")
        }
    }
    val regex = try {
        Regex(prefix + re.value, options)
    } catch (e: Exception) {
        fail("${re.value} (at offset 0) is not a valid regex: ${e.message}")
    }
    val compiled = CompiledRegex(regex, global, skipEmpty, groupNames(re.value))
    if (regexCache.size > 256) regexCache.clear()
    regexCache[cacheKey] = compiled
    return compiled
}

/** Names of the capturing groups in order (null for unnamed ones). */
private fun groupNames(p: String): List<String?> {
    val names = ArrayList<String?>()
    var i = 0
    var inClass = false
    while (i < p.length) {
        val c = p[i]
        when {
            c == '\\' -> {
                i += 2
                continue
            }
            inClass -> if (c == ']') inClass = false
            c == '[' -> {
                inClass = true
                if (i + 1 < p.length && p[i + 1] == '^') i++
                if (i + 1 < p.length && p[i + 1] == ']') i++
            }
            c == '(' -> {
                if (i + 1 >= p.length || p[i + 1] != '?') {
                    names += null
                } else if (p.startsWith("(?<", i) && i + 3 < p.length && p[i + 3] != '=' && p[i + 3] != '!') {
                    val end = p.indexOf('>', i + 3)
                    if (end > 0) names += p.substring(i + 3, end)
                } else if (p.startsWith("(?P<", i)) {
                    val end = p.indexOf('>', i + 4)
                    if (end > 0) names += p.substring(i + 4, end)
                }
            }
        }
        i++
    }
    return names
}

internal fun matches(r: CompiledRegex, s: String, forceGlobal: Boolean = false): List<MatchResult> {
    val all = if (r.global || forceGlobal) r.regex.findAll(s).toList() else listOfNotNull(r.regex.find(s))
    return if (r.skipEmpty) all.filter { it.value.isNotEmpty() } else all
}

internal fun matchObject(r: CompiledRegex, m: MatchResult): JsonObject {
    val captures = ArrayList<JsonValue>()
    for (g in 1 until m.groups.size) {
        val group = m.groups[g]
        val name = r.groupNames.getOrNull(g - 1)
        captures += JsonObject(
            linkedMapOf(
                "offset" to (if (group == null) num(-1L) else num(group.range.first.toLong())),
                "length" to num((group?.value?.length ?: 0).toLong()),
                "string" to (group?.value?.let { JsonString(it) } ?: JsonNull),
                "name" to (name?.let { JsonString(it) } ?: JsonNull),
            ),
        )
    }
    return JsonObject(
        linkedMapOf(
            "offset" to num(m.range.first.toLong()),
            "length" to num(m.value.length.toLong()),
            "string" to JsonString(m.value),
            "captures" to JsonArray(captures),
        ),
    )
}

internal fun captureObject(r: CompiledRegex, m: MatchResult): JsonObject {
    val out = LinkedHashMap<String, JsonValue>()
    for (g in 1 until m.groups.size) {
        val name = r.groupNames.getOrNull(g - 1) ?: continue
        out[name] = m.groups[g]?.value?.let { JsonString(it) } ?: JsonNull
    }
    return JsonObject(out)
}
