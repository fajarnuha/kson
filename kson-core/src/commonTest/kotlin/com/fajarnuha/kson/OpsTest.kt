package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals

class OpsTest {
    @Test
    fun plusMinus() {
        val a = json { "x" to 1; "y" to 2 }
        assertEquals("""{"x":1,"y":3,"z":4}""", (a + json { "y" to 3; "z" to 4 }).toJson())
        assertEquals("""{"x":1,"y":2,"k":"v"}""", (a + ("k" to "v")).toJson())
        assertEquals("""{"y":2}""", (a - "x").toJson())
        assertEquals("""{"x":1,"y":null}""", a.with("y", null).toJson())
        assertEquals("[1,2,3]", (jsonArray(1) + jsonArray(2) + 3).toJson())
    }

    @Test
    fun deepMerge() {
        val base = json { "a" { "b" to 1; "c" to 2 }; "l" to arr(1, 2) }
        val over = json { "a" { "c" to 3; "d" to 4 }; "l" to arr(9) }
        assertEquals("""{"a":{"b":1,"c":3,"d":4},"l":[9]}""", base.deepMerge(over).toJson())
    }

    @Test
    fun sortedAndWithoutNulls() {
        val v = Json.parse("""{"b":null,"a":{"z":1,"y":null},"c":[null,{"q":null}]}""")
        assertEquals("""{"a":{"y":null,"z":1},"b":null,"c":[null,{"q":null}]}""", v.sortedKeys().toJson())
        assertEquals("""{"a":{"z":1},"c":[null,{}]}""", v.withoutNulls().toJson())
    }

    @Test
    fun walkVisitsEveryNodeWithPointer() {
        val seen = mutableListOf<String>()
        Json.parse("""{"a":[1,{"b/c":2}]}""").walk { p, _ -> seen += p }
        assertEquals(listOf("", "/a", "/a/0", "/a/1", "/a/1/b~1c"), seen)
    }
}
