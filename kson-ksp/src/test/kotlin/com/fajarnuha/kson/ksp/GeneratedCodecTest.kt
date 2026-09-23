package com.fajarnuha.kson.ksp

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonNull
import com.fajarnuha.kson.JsonNumber
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.Kson
import com.fajarnuha.kson.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@Kson
interface Everything {
    val text: String
    val flag: Boolean
    val count: Int
    val big: Long
    val ratio: Float
    val score: Double
    val exact: JsonNumber
    val extra: JsonObject
    val items: JsonArray
    val any: JsonValue
    val color: Color
    val tags: List<String>
    val matrix: List<List<Int>>
    val children: List<Child>
    val child: Child
    val empty: Empty
    val maybeText: String?
    val maybeChild: Child?
    val maybeTags: List<String?>?
    val maybeColor: Color?

    interface Child {
        val name: String
    }

    interface Empty

    enum class Color { RED, GREEN }
}

class GeneratedCodecTest {
    @Test
    fun encodeRoundTripsEveryPropertyType() {
        val json = Json.parse(
            """
            {"text":"a\"b","flag":true,"count":-3,"big":9007199254740993,"ratio":1.5,"score":0.1,
             "exact":12345678901234567890,"extra":{"k":[1]},"items":[null,"x"],"any":null,"color":"GREEN",
             "tags":["a","b"],"matrix":[[1,2],[]],"children":[{"name":"c1"}],"child":{"name":"c2"},"empty":{},
             "maybeText":"m","maybeChild":{"name":"c3"},"maybeTags":["t",null],"maybeColor":"RED"}
            """,
        )
        assertEquals(json, EverythingJson.encode(EverythingJson.decode(json)))
    }

    @Test
    fun encodesMissingNullablePropertiesAsNull() {
        val json = Json.parse(
            """
            {"text":"","flag":false,"count":0,"big":0,"ratio":0,"score":0,"exact":0,"extra":{},"items":[],
             "any":{},"color":"RED","tags":[],"matrix":[],"children":[],"child":{"name":""},"empty":{}}
            """,
        ) as JsonObject
        val encoded = EverythingJson.encode(EverythingJson.decode(json))
        assertEquals(json.keys + listOf("maybeText", "maybeChild", "maybeTags", "maybeColor"), encoded.keys)
        assertEquals(listOf<JsonValue>(JsonNull, JsonNull, JsonNull, JsonNull), encoded.values.drop(json.size))
    }

    @Test
    fun encodesInstancesImplementedByHand() {
        val child = object : Everything.Child {
            override val name = "hand"
        }
        val encoded = EverythingJson.encode(object : Everything {
            override val text = "t"
            override val flag = true
            override val count = 1
            override val big = 2L
            override val ratio = 0.5f
            override val score = 2.25
            override val exact = JsonNumber.parse("1e3")
            override val extra = JsonObject.Empty
            override val items = JsonArray.Empty
            override val any: JsonValue = JsonNull
            override val color = Everything.Color.RED
            override val tags = listOf("x")
            override val matrix = listOf(listOf(1))
            override val children = listOf(child)
            override val child = child
            override val empty = object : Everything.Empty {}
            override val maybeText = null
            override val maybeChild = child
            override val maybeTags = listOf(null, "y")
            override val maybeColor = null
        })
        assertEquals(
            Json.parse(
                """
                {"text":"t","flag":true,"count":1,"big":2,"ratio":0.5,"score":2.25,"exact":1e3,"extra":{},"items":[],
                 "any":null,"color":"RED","tags":["x"],"matrix":[[1]],"children":[{"name":"hand"}],
                 "child":{"name":"hand"},"empty":{},"maybeText":null,"maybeChild":{"name":"hand"},
                 "maybeTags":[null,"y"],"maybeColor":null}
                """,
            ),
            encoded,
        )
    }

    @Test
    fun buildsInstancesWithTheDsl() {
        val existing = everythingKson { requiredOnly() }.child
        val built = everythingKson {
            text = "t"
            flag = true
            count = 1
            big = 2
            ratio = 0.5f
            score = 2.25
            exact = JsonNumber.parse("1e3")
            extra = json { "k" to 1 }
            items = JsonArray.Empty
            any = JsonNull
            color = Everything.Color.RED
            tags = listOf("x")
            matrix = listOf(listOf(1))
            children {
                add { name = "first" }
                add(existing)
            }
            child { name = "hand" }
            empty { }
            maybeChild = existing
            maybeTags = listOf(null, "y")
        }
        assertEquals(
            Json.parse(
                """
                {"text":"t","flag":true,"count":1,"big":2,"ratio":0.5,"score":2.25,"exact":1e3,"extra":{"k":1},
                 "items":[],"any":null,"color":"RED","tags":["x"],"matrix":[[1]],
                 "children":[{"name":"first"},{"name":"required"}],"child":{"name":"hand"},"empty":{},
                 "maybeText":null,"maybeChild":{"name":"required"},"maybeTags":[null,"y"],"maybeColor":null}
                """,
            ),
            EverythingJson.encode(built),
        )
        assertSame(existing, built.maybeChild)
    }

    @Test
    fun builtInstancesAreValueObjects() {
        val first = everythingKson { requiredOnly() }
        val second = everythingKson { requiredOnly() }
        assertEquals(first.child, second.child)
        assertNull(first.maybeText)
    }

    @Test
    fun reportsMissingRequiredProperties() {
        val error = assertFailsWith<IllegalStateException> {
            everythingKson {
                requiredOnly()
                child { }
            }
        }
        assertEquals("Everything.Child.name is not set", error.message)
        assertFailsWith<IllegalStateException> { everythingKson { text = "only" } }
    }

    @Test
    fun readsBackAssignedProperties() {
        everythingKson {
            val error = assertFailsWith<IllegalStateException> { text }
            assertEquals("Everything.text is not set", error.message)
            requiredOnly()
            count = 7
            assertEquals(7, count)
            assertNull(maybeColor)
        }
    }
}

private fun EverythingJson.EverythingBuilder.requiredOnly() {
    text = ""
    flag = false
    count = 0
    big = 0
    ratio = 0f
    score = 0.0
    exact = JsonNumber(0)
    extra = JsonObject.Empty
    items = JsonArray.Empty
    any = JsonNull
    color = Everything.Color.GREEN
    tags = emptyList()
    matrix = emptyList()
    children = emptyList()
    child { name = "required" }
    empty { }
}
