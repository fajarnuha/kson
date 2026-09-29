package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParserTest {
    @Test
    fun scalarsAtTopLevel() {
        assertSame(JsonNull, Json.parse("null"))
        assertEquals(JsonBool(true), Json.parse(" true "))
        assertEquals(JsonBool(false), Json.parse("false"))
        assertEquals(JsonNumber(-12.5), Json.parse("-12.5"))
        assertEquals(JsonString("hi"), Json.parse("\"hi\""))
    }

    @Test
    fun containers() {
        val v = Json.parse(""" { "a" : [1, 2, {"b": null}], "c": {} , "d": [] } """)
        assertEquals("""{"a":[1,2,{"b":null}],"c":{},"d":[]}""", v.toJson())
        assertEquals(listOf("a", "c", "d"), v.jsonObject.keys.toList())
    }

    @Test
    fun stringEscapes() {
        val v = Json.parse(""""\" \\ \/ \b \f \n \r \t \u0041 \u00e9 \ud83d\ude00"""")
        assertEquals("\" \\ / \b \u000C \n \r \t A é 😀", v.string)
    }

    @Test
    fun unicodePassesThrough() {
        assertEquals("日本語 😀", Json.parse("\"日本語 😀\"").string)
    }

    @Test
    fun duplicateKeys() {
        assertEquals("""{"a":2}""", Json.parse("""{"a":1,"a":2}""").toJson())
        val e = assertFailsWith<JsonParseException> { Json.parse("""{"a":1,"a":2}""", JsonParseOptions.Strict) }
        assertTrue("Duplicate" in e.message!!)
    }

    @Test
    fun byteOrderMarkTolerated() {
        assertEquals(JsonNumber(1), Json.parse("\uFEFF1"))
    }

    @Test
    fun rejectsInvalidJson() {
        val bad = listOf(
            "", " ", "{", "}", "[", "[1,]", "[,1]", "{\"a\":1,}", "{a:1}", "{'a':1}", "'x'",
            "tru", "nul", "True", "NaN", "Infinity", "-Infinity", "01", "1.", ".1", "+1", "0x1", "1e",
            "\"abc", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"a\nb\"", "\"\t\"", "[1 2]", "{\"a\" 1}",
            "{\"a\":1 \"b\":2}", "1 2", "[] []", "/* c */ 1", "// c\n1", "[1]x", "\u00A01",
        )
        for (text in bad) {
            assertFailsWith<JsonParseException>("should reject: $text") { Json.parse(text) }
            assertFalse(Json.isValid(text), text)
            assertNull(text.parseJsonOrNull())
        }
    }

    @Test
    fun errorPositions() {
        val e = assertFailsWith<JsonParseException> { Json.parse("{\n  \"a\": tru\n}") }
        assertEquals(2, e.line)
        assertEquals(8, e.column)
        assertEquals(9, e.offset)
    }

    @Test
    fun depthLimit() {
        val deep = "[".repeat(600) + "]".repeat(600)
        assertFailsWith<JsonParseException> { Json.parse(deep) }
        assertTrue(Json.isValid(deep, JsonParseOptions(maxDepth = 1000)))
        val ok = "[".repeat(512) + "]".repeat(512)
        assertTrue(Json.isValid(ok))
    }

    @Test
    fun typedEntryPoints() {
        assertEquals(1, Json.parseObject("""{"a":1}""")["a"]?.int)
        assertEquals(2, Json.parseArray("[1,2]").size)
        assertFailsWith<JsonTypeException> { Json.parseObject("[1]") }
        assertFailsWith<JsonTypeException> { Json.parseArray("{}") }
    }

    @Test
    fun minifyAndPrettify() {
        assertEquals("""{"a":[1,2]}""", Json.minify("{ \"a\" : [ 1 , 2 ] }"))
        assertEquals("{\n    \"a\": 1\n}", Json.prettify("""{"a":1}""", indent = "    "))
    }

    @Test
    fun roundTripIsStable() {
        val samples = listOf(
            """{"a":1,"b":[true,false,null],"c":{"d":"e\"f","g":-0.5e-10}}""",
            """[[],{},"",0,-0,1E400,"\u0000"]""",
            """"line\nbreak"""",
        )
        for (s in samples) {
            val once = Json.parse(s)
            val twice = Json.parse(once.toJson())
            assertEquals(once, twice)
            assertEquals(once.toJson(), twice.toJson())
            assertEquals(once, Json.parse(once.toJson(pretty = true)))
        }
    }
}

class StreamParserTest {
    @Test
    fun parsesWhitespaceSeparatedValues() {
        assertEquals(listOf("1", "{\"a\":2}", "[3]", "\"x\"", "null"), Json.parseAll("1 {\"a\":2}\n[3]\"x\"\tnull\n").map { it.toJson() })
        assertEquals(emptyList(), Json.parseAll("  \n"))
    }

    @Test
    fun errorsSurfaceLazily() {
        val seq = Json.parseSequence("1 2 {oops").iterator()
        assertEquals("1", seq.next().toJson())
        assertEquals("2", seq.next().toJson())
        kotlin.test.assertFailsWith<JsonParseException> { seq.next() }
    }
}
