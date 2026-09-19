package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessTest {
    private val doc = Json.parse(
        """{"name":"kson","n":3,"big":10000000000,"f":1.5,"ok":true,"nil":null,
           "list":[10,{"deep":"yes"}],"a/b":1,"m~n":2,"":0}""",
    )

    @Test
    fun typedAccessors() {
        assertEquals("kson", doc["name"]!!.string)
        assertEquals(3, doc["n"]!!.int)
        assertEquals(10000000000L, doc["big"]!!.long)
        assertNull(doc["big"]!!.intOrNull)
        assertEquals(1.5, doc["f"]!!.double)
        assertNull(doc["f"]!!.intOrNull)
        assertTrue(doc["ok"]!!.boolean)
        assertTrue(doc["nil"]!!.isNull)
        assertEquals("yes", doc["list"][1]["deep"]?.string)
        assertNull(doc["list"][5])
        assertNull(doc["missing"]["x"][0])
        assertEquals("object", doc.jsonType)
    }

    @Test
    fun mismatchThrows() {
        assertFailsWith<JsonTypeException> { doc["name"]!!.int }
        assertFailsWith<JsonTypeException> { doc["n"]!!.string }
        assertFailsWith<JsonTypeException> { doc.jsonArray }
        assertFailsWith<JsonTypeException> { doc.jsonObject.require("nope") }
        assertNull(doc["name"]!!.booleanOrNull)
    }

    @Test
    fun hasNonNull() {
        assertTrue(doc.jsonObject.hasNonNull("n"))
        assertFalse(doc.jsonObject.hasNonNull("nil"))
        assertFalse(doc.jsonObject.hasNonNull("nope"))
    }

    @Test
    fun jsonPointer() {
        assertEquals(doc, doc.at(""))
        assertEquals(JsonNumber(10), doc.at("/list/0"))
        assertEquals("yes", doc.at("/list/1/deep")?.string)
        assertEquals(JsonNumber(1), doc.at("/a~1b"))
        assertEquals(JsonNumber(2), doc.at("/m~0n"))
        assertEquals(JsonNumber(0), doc.at("/"))
        assertNull(doc.at("/list/01"))
        assertNull(doc.at("/list/-"))
        assertNull(doc.at("/name/x"))
        assertFailsWith<JsonException> { doc.at("list") }
        assertFailsWith<JsonException> { doc.at("/a~2") }
        assertFailsWith<JsonTypeException> { doc.requireAt("/zzz") }
        assertEquals("/a~1b/0/m~0n", JsonPointer.of("a/b", 0, "m~n"))
    }
}
