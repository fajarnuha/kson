package com.fajarnuha.kson

/**
 * A JSON number.
 *
 * The number is stored as its JSON [literal] text so that parsing and re-serialising never loses
 * precision (e.g. `12345678901234567890` or `0.1000000000000000055511151231257827`). Two numbers are
 * equal when they denote the same mathematical value: `1`, `1.0`, `1e0` and `0.1e1` are all equal.
 *
 * `JsonNumber` is itself a [Number], so it can be used anywhere a Kotlin number is expected.
 */
public class JsonNumber private constructor(
    /** The exact JSON text of this number, e.g. `-12.5e3`. */
    public val literal: String,
    private val canonical: String,
) : Number(), JsonValue, Comparable<JsonNumber> {

    /** Wraps a Kotlin number. Throws [IllegalArgumentException] for NaN and infinities, which JSON cannot represent. */
    public constructor(value: Number) : this(literalOf(value))

    private constructor(literal: String) : this(literal, canonicalize(literal))

    /** Whether this number has no fractional part (`1`, `1.0`, `1e2` and `100` are all integral). */
    public val isIntegral: Boolean get() = canonicalExponent >= 0

    private val canonicalExponent: Long get() = canonical.substring(canonical.indexOf('E') + 1).toLong()

    private val doubleValue: Double by lazy { literal.toDouble() }

    /**
     * The most natural Kotlin representation: a [Long] when the number is integral and fits, otherwise a [Double].
     */
    public val value: Number
        get() = toLongOrNull() ?: doubleValue

    /** Returns this number as a [Long] when it is integral and fits, or `null` otherwise. */
    public fun toLongOrNull(): Long? {
        val e = canonical.indexOf('E')
        val exp = canonical.substring(e + 1).toLong()
        if (exp < 0) return null
        val mantissa = canonical.substring(0, e)
        val digitCount = mantissa.length - (if (mantissa.startsWith('-')) 1 else 0)
        if (digitCount + exp > 19) return null
        val sb = StringBuilder(mantissa)
        repeat(exp.toInt()) { sb.append('0') }
        return sb.toString().toLongOrNull()
    }

    /** Returns this number as an [Int] when it is integral and fits, or `null` otherwise. */
    public fun toIntOrNull(): Int? = toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

    override fun toDouble(): Double = doubleValue
    override fun toFloat(): Float = doubleValue.toFloat()
    override fun toLong(): Long = toLongOrNull() ?: doubleValue.toLong()
    override fun toInt(): Int = toLongOrNull()?.toInt() ?: doubleValue.toInt()
    override fun toShort(): Short = toInt().toShort()
    override fun toByte(): Byte = toInt().toByte()

    override fun compareTo(other: JsonNumber): Int {
        if (canonical == other.canonical) return 0
        val a = toLongOrNull()
        val b = other.toLongOrNull()
        if (a != null && b != null) return a.compareTo(b)
        return doubleValue.compareTo(other.doubleValue)
    }

    override fun equals(other: Any?): Boolean = other is JsonNumber && canonical == other.canonical
    override fun hashCode(): Int = canonical.hashCode()
    override fun toString(): String = literal

    public companion object {
        /** Parses a JSON number literal. Throws [JsonException] if [literal] is not a valid JSON number. */
        public fun parse(literal: String): JsonNumber {
            if (!isValidLiteral(literal)) throw JsonException("Invalid JSON number literal: '$literal'")
            return JsonNumber(literal)
        }

        /** Parses a JSON number literal, or returns `null` if it is not a valid JSON number. */
        public fun parseOrNull(literal: String): JsonNumber? = if (isValidLiteral(literal)) JsonNumber(literal) else null

        /** Creates a number from text that has already been validated by the parser. */
        internal fun unchecked(literal: String): JsonNumber = JsonNumber(literal)

        /** Checks [s] against the RFC 8259 number grammar: `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. */
        public fun isValidLiteral(s: String): Boolean {
            var i = 0
            val n = s.length
            if (n == 0) return false
            if (s[i] == '-') i++
            if (i >= n) return false
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < n && s[i] in '0'..'9') i++
            } else {
                return false
            }
            if (i < n && s[i] == '.') {
                i++
                if (i >= n || s[i] !in '0'..'9') return false
                while (i < n && s[i] in '0'..'9') i++
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                if (i >= n || s[i] !in '0'..'9') return false
                while (i < n && s[i] in '0'..'9') i++
            }
            return i == n
        }

        private fun literalOf(value: Number): String = when (value) {
            is JsonNumber -> value.literal
            is Int, is Long, is Short, is Byte -> value.toString()
            is Double -> finiteLiteral(value)
            is Float -> {
                require(!value.isNaN() && !value.isInfinite()) { "Non-finite numbers cannot be represented in JSON: $value" }
                value.toString()
            }
            else -> value.toString().takeIf(::isValidLiteral) ?: finiteLiteral(value.toDouble())
        }

        private fun finiteLiteral(d: Double): String {
            require(!d.isNaN() && !d.isInfinite()) { "Non-finite numbers cannot be represented in JSON: $d" }
            return d.toString()
        }

        /**
         * Normalises a valid literal to `[-]<digits>E<exp>` with no leading/trailing zeros in the mantissa,
         * so that mathematically equal literals produce identical strings. Zero is always `0E0`.
         */
        internal fun canonicalize(lit: String): String {
            var i = 0
            val negative = lit[0] == '-'
            if (negative) i++
            val digits = StringBuilder()
            while (i < lit.length && lit[i] in '0'..'9') digits.append(lit[i++])
            val intLen = digits.length
            if (i < lit.length && lit[i] == '.') {
                i++
                while (i < lit.length && lit[i] in '0'..'9') digits.append(lit[i++])
            }
            var exp = 0L
            if (i < lit.length && (lit[i] == 'e' || lit[i] == 'E')) {
                val expText = lit.substring(i + 1)
                exp = expText.toLongOrNull() ?: if (expText.startsWith('-')) Long.MIN_VALUE / 4 else Long.MAX_VALUE / 4
            }
            exp -= (digits.length - intLen)

            var start = 0
            while (start < digits.length && digits[start] == '0') start++
            var end = digits.length
            while (end > start && digits[end - 1] == '0') {
                end--
                exp++
            }
            if (start == end) return "0E0"
            val mantissa = digits.substring(start, end)
            return (if (negative) "-" else "") + mantissa + "E" + exp
        }
    }
}
