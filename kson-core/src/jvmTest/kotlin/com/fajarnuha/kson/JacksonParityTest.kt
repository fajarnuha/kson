package com.fajarnuha.kson

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-checks kson against Jackson (strict mode) to prove it accepts, rejects and round-trips
 * the same documents as a mainstream RFC 8259 implementation.
 */
class JacksonParityTest {
    private val jackson = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        .build()

    private fun jacksonAccepts(text: String): Boolean = try {
        // Jackson answers empty input with a MissingNode rather than an exception.
        val node = jackson.readTree(text)
        node != null && !node.isMissingNode
    } catch (_: Exception) {
        false
    }

    /** Documents every RFC 8259 parser must accept (subset of JSONTestSuite's y_ cases). */
    private val valid = listOf(
        "[]", "{}", "null", "true", "false", "0", "-0", "1", "-1", "0.5", "-0.0", "1e1", "1E1", "1e+1", "1e-1",
        "1.5e308", "123456789012345678901234567890", "-123e-2", "\"\"", "\" \"", "\"\\u0000\"", "\"\\uD834\\uDD1E\"",
        "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"", "\"日本\"", "\"😀\"", "[[[[[[]]]]]]", "[1,[2,[3]]]", " [ 1 , 2 ] ",
        "{\"a\":{\"b\":{\"c\":[]}}}", "{\"\":0}", "{\"a\":null,\"b\":true}", "\n\t\r [1]\n", "[-1e-10,2E+5]",
        "{\"k\":\"v\",\"n\":[1,2.5,-3e2,{\"x\":[]}]}", "[\"\\u00e9\",\"\\u20ac\"]", "1E400", "[1e-400]",
    )

    /** Documents every RFC 8259 parser must reject (subset of JSONTestSuite's n_ cases). */
    private val invalid = listOf(
        "", " ", "[", "]", "{", "}", "[1,]", "[,]", "[1,,2]", "{,}", "{\"a\":1,}", "{\"a\"}", "{\"a\":}", "{1:1}",
        "{a:1}", "{'a':1}", "['a']", "[01]", "[1.]", "[.1]", "[+1]", "[1e]", "[1e+]", "[-]", "[0x1]", "[NaN]",
        "[Infinity]", "[-Infinity]", "[tru]", "[nul]", "[True]", "[\"\\x\"]", "[\"\\u00G0\"]", "[\"\\u0\"]",
        "[\"a\nb\"]", "[\"\u0001\"]", "[\"abc]", "[1 2]", "[1]]", "[1]x", "{\"a\":1}}", "1 2", "/**/[]", "[]//",
        "[1,]\n", "\u000B[]", "\u00A0[]", "[\"\\\"]",
    )

    @Test
    fun acceptsWhatJacksonAccepts() {
        for (text in valid) {
            assertTrue(jacksonAccepts(text), "corpus sanity: jackson should accept $text")
            try {
                Json.parse(text)
            } catch (e: JsonParseException) {
                fail("kson rejected valid JSON $text: ${e.message}")
            }
        }
    }

    @Test
    fun rejectsWhatJacksonRejects() {
        for (text in invalid) {
            assertTrue(!jacksonAccepts(text), "corpus sanity: jackson should reject ${text.escape()}")
            assertTrue(!Json.isValid(text), "kson accepted invalid JSON ${text.escape()}")
        }
    }

    @Test
    fun parsedTreesMatchJackson() {
        for (text in valid + extraDocs) {
            val expected = jackson.readTree(text)
            val actual = jackson.readTree(Json.parse(text).toJson())
            assertEquals(expected.normalized(), actual.normalized(), "tree mismatch for $text")
            val pretty = jackson.readTree(Json.parse(text).toJson(pretty = true))
            assertEquals(expected.normalized(), pretty.normalized(), "pretty tree mismatch for $text")
        }
    }

    @Test
    fun builderOutputIsReadByJackson() {
        val built = json {
            "s" to "q\"uote\\ \n\u0001 é 😀"
            "i" to Long.MAX_VALUE
            "d" to 0.1
            "b" to false
            "n" to null
            "a" to arr { add(1); obj { "x" to arr() } }
        }
        val node = jackson.readTree(built.toJson())
        assertEquals("q\"uote\\ \n\u0001 é 😀", node["s"].asText())
        assertEquals(Long.MAX_VALUE, node["i"].bigIntegerValue().toLong())
        assertEquals(0.1, node["d"].doubleValue())
        assertTrue(node["n"].isNull)
        assertEquals(0, node["a"][1]["x"].size())
    }

    @Test
    fun writerMatchesJacksonCompactOutputForStrings() {
        // Jackson's compact output for plain strings/containers should be byte-identical to kson's.
        val docs = listOf("""{"a":[1,2,{"b":"c"}],"d":"e\nf\"g"}""", """["\t","\\","/"]""")
        for (d in docs) {
            assertEquals(jackson.writeValueAsString(jackson.readTree(d)), Json.minify(d))
        }
    }

    private val extraDocs = listOf(
        """{"users":[{"id":1,"tags":["a","b"],"geo":{"lat":-6.29,"lng":106.72}},{"id":2,"tags":[],"geo":null}]}""",
        """{"dup":1,"dup":2}""",
    )

    /** Compares numbers by value so `1.0` and `1` or `1e2` and `100` are the same. */
    private fun JsonNode.normalized(): Any? = when {
        isObject -> properties().associate { it.key to it.value.normalized() }
        isArray -> map { it.normalized() }
        isNumber -> decimalValue().stripTrailingZeros().let { if (it.signum() == 0) java.math.BigDecimal.ZERO else it }
        isTextual -> textValue()
        isBoolean -> booleanValue()
        isNull -> null
        else -> error("unexpected node $this")
    }

    private fun String.escape() = buildString { for (c in this@escape) if (c < ' ' || c > '~') append("\\u%04x".format(c.code)) else append(c) }
}
