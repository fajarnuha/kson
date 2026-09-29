package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private enum class Role { ADMIN, USER }

class BuilderTest {
    @Test
    fun simpleShape() {
        val j = json {
            "a" to "b"
            "c" to obj { }
        }
        assertEquals("""{"a":"b","c":{}}""", j.toJson())
        assertEquals(j.toJson(), j.toString())
    }

    @Test
    fun threeNestingStylesAreEquivalent() {
        val a = json { "x" to obj { "y" to 1 } }
        val b = json { "x" to { "y" to 1 } }
        val c = json { "x" { "y" to 1 } }
        assertEquals(a, b)
        assertEquals(b, c)
        assertEquals("""{"x":{"y":1}}""", c.toJson())
    }

    @Test
    fun scalarsAndArrays() {
        val user = json {
            "id" to 42
            "name" to "Fajar"
            "active" to true
            "nickname" to null
            "role" to Role.ADMIN
            "score" to 9.5
            "tags" to arr("kotlin", "android")
            "devices" to arr {
                obj { "os" to "android"; "v" to 15 }
                +"raw string element"
                add(3)
                arr(1, 2)
            }
        }
        assertEquals(
            """{"id":42,"name":"Fajar","active":true,"nickname":null,"role":"ADMIN","score":9.5,""" +
                """"tags":["kotlin","android"],"devices":[{"os":"android","v":15},"raw string element",3,[1,2]]}""",
            user.toJson(),
        )
    }

    @Test
    fun insertionOrderIsPreservedAndLastWriteWins() {
        val j = json {
            "b" to 1
            "a" to 2
            "b" to 3
        }
        assertEquals("""{"b":3,"a":2}""", j.toJson())
    }

    @Test
    fun existingKotlinDataDropsIn() {
        val config = mapOf("retries" to 3, "hosts" to listOf("a", "b"))
        val j = json {
            "config" to config
            "matrix" to listOf(listOf(1, 2), listOf(3, 4))
            "ints" to intArrayOf(1, 2)
            "seq" to sequenceOf(true, false)
            "pair" to Pair("k", 'v')
            "ulong" to ULong.MAX_VALUE
        }
        assertEquals(
            """{"config":{"retries":3,"hosts":["a","b"]},"matrix":[[1,2],[3,4]],"ints":[1,2],""" +
                """"seq":[true,false],"pair":{"k":"v"},"ulong":18446744073709551615}""",
            j.toJson(),
        )
    }

    @Test
    fun topLevelArrays() {
        assertEquals("""[1,"two",{"three":3}]""", jsonArray { add(1); +"two"; obj { "three" to 3 } }.toJson())
        assertEquals("""[1,null,"x"]""", jsonArray(1, null, "x").toJson())
        assertEquals("""[]""", jsonArray().toJson())
    }

    @Test
    fun helpers() {
        // Build maps with `to` OUTSIDE the block: inside it, `to` is the DSL's.
        val extra = mapOf("d" to 4)
        val j = json {
            put("a", 1)
            putIfNotNull("b", null)
            putIfNotNull("c", "yes")
            putAll(extra)
            "gone" to 1
            remove("gone")
        }
        assertEquals("""{"a":1,"c":"yes","d":4}""", j.toJson())
        assertEquals(jsonObjectOf("a" to 1), json { "a" to 1 })
        assertEquals(jsonArrayOf(1, 2), jsonArray(1, 2))
    }

    @Test
    fun buildUponCopies() {
        val base = json { "a" to 1 }
        val next = base.buildUpon { "b" to 2 }
        assertEquals("""{"a":1}""", base.toJson())
        assertEquals("""{"a":1,"b":2}""", next.toJson())
        assertEquals("[1,2]", jsonArray(1).buildUpon { add(2) }.toJson())
    }

    @Test
    fun builtValuesAreSnapshots() {
        val list = mutableListOf(1)
        val j = json { "l" to list }
        list += 2
        assertEquals("""{"l":[1]}""", j.toJson())
    }

    @Test
    fun readingValuesBack() {
        val user = json { "address" { "city" to "Tangerang Selatan" } }
        assertEquals("Tangerang Selatan", user["address"]["city"]?.string)
        assertEquals(JsonString("Tangerang Selatan"), (user["address"] as JsonObject)["city"])
    }

    @Test
    fun conversionRoundTrip() {
        val data = mapOf("a" to listOf(1L, 2.5, null, true), "b" to mapOf("c" to "d"))
        assertEquals(data, data.toJsonValue().toKotlin())
        assertSame(JsonNull, null.toJsonValue())
        assertTrue(Any().toJsonValue() is JsonString)
    }

    @Test
    fun prettyPrinting() {
        val j = json {
            "a" to 1
            "b" to arr(1, 2)
            "c" to obj { }
            "d" to arr()
        }
        val expected = """
            {
              "a": 1,
              "b": [
                1,
                2
              ],
              "c": {},
              "d": []
            }
        """.trimIndent()
        assertEquals(expected, j.toJson(pretty = true))
    }
}
