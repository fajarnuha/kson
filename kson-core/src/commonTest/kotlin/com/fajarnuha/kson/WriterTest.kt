package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals

class WriterTest {
    @Test
    fun escapesControlCharacters() {
        val s = JsonString("\"\\/\b\u000C\n\r\t\u0000\u001f\u007f")
        assertEquals("\"\\\"\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f\\u007f\"", s.toJson())
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

class ColorWriterTest {
    @Test
    fun colorsWrapEveryToken() {
        val out = json { "k" to arr(1, "s", null, true) }.toJson(JsonFormat(colors = JsonColors.Default))
        val esc = "\u001B["
        assertEquals(
            "${esc}1;39m{${esc}0m${esc}34;1m\"k\"${esc}0m${esc}1;39m:${esc}0m${esc}1;39m[${esc}0m" +
                "${esc}0;39m1${esc}0m${esc}1;39m,${esc}0m${esc}0;32m\"s\"${esc}0m${esc}1;39m,${esc}0m" +
                "${esc}0;90mnull${esc}0m${esc}1;39m,${esc}0m${esc}0;39mtrue${esc}0m${esc}1;39m]${esc}0m${esc}1;39m}${esc}0m",
            out,
        )
    }

    @Test
    fun jqColorsSpec() {
        val c = JsonColors.parse("0;31::::0;35")!!
        assertEquals("0;31", c.nullColor)
        assertEquals("0;35", c.stringColor)
        assertEquals(JsonColors.Default.keyColor, c.keyColor)
        kotlin.test.assertNull(JsonColors.parse("red"))
    }

    @Test
    fun deleteCharacterIsEscaped() {
        assertEquals("\"\\u007f\"", JsonString("\u007F").toJson())
    }
}
