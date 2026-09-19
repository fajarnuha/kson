package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals

class WriterTest {
    @Test
    fun escapesControlCharacters() {
        val s = JsonString("\"\\/\b\u000C\n\r\t\u0000\u001f\u007f")
        assertEquals("\"\\\"\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f\u007f\"", s.toJson())
    }

    @Test
    fun nonAsciiIsKeptByDefault() {
        assertEquals("\"é😀\"", JsonString("é😀").toJson())
    }

    @Test
    fun asciiOnlyOutput() {
        val out = JsonString("é😀").toJson(JsonFormat(escapeNonAscii = true))
        assertEquals("\"\\u00e9\\ud83d\\ude00\"", out)
        assertEquals("é😀", Json.parse(out).string)
    }

    @Test
    fun sortKeysAndCustomIndent() {
        val j = json {
            "b" to 1
            "a" { "d" to 1; "c" to 2 }
        }
        assertEquals("""{"a":{"c":2,"d":1},"b":1}""", j.toJson(JsonFormat(sortKeys = true)))
        assertEquals("{\n\t\"b\": 1,\n\t\"a\": {\n\t\t\"d\": 1,\n\t\t\"c\": 2\n\t}\n}", j.toJson(JsonFormat(pretty = true, indent = "\t")))
    }

    @Test
    fun numbersUseTheirLiteral() {
        assertEquals("[1,1.5,-3,1.0E20,2.5]", jsonArray(1, 1.5, -3L, 1e20, 2.5f).toJson())
    }
}
