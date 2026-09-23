package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@Kson
interface Point {
    val x: Int
}

object PointJson : KsonDecoder<Point>, KsonEncoder<Point> {
    override val schema: JsonObject = JsonObject.Empty

    override fun decode(value: JsonValue): Point {
        val x = value.jsonObject.require("x").int
        return object : Point {
            override val x = x
        }
    }

    override fun encode(value: Point): JsonObject = JsonObject(mapOf("x" to JsonNumber(value.x)))
}

data class PointImpl(override val x: Int) : Point

@Kson
interface Undecoded

interface Plain

class KsonTypesTest {
    @Suppress("unused")
    private fun signatures(points: List<Point>, wildcard: List<out Point>, map: Map<String, Point>) = Unit

    private fun parameterType(index: Int) =
        KsonTypesTest::class.java.getDeclaredMethod("signatures", List::class.java, List::class.java, Map::class.java)
            .genericParameterTypes[index]

    @Test
    fun findsGeneratedDecoders() {
        assertSame(PointJson, ksonDecoderOf(Point::class.java))
        assertNull(ksonDecoderOf(Plain::class.java))
        val error = assertFailsWith<IllegalStateException> { ksonDecoderOf(Undecoded::class.java) }
        assertEquals(true, error.message?.contains("com.fajarnuha.kson.UndecodedJson"))
    }

    @Test
    fun readsKsonInterfacesListsAndValues() {
        assertEquals(3, (ksonReaderOf(Point::class.java)!!(Json.parse("""{"x":3}""")) as Point).x)
        assertNull(ksonReaderOf(Point::class.java)!!(JsonNull))

        val points = Json.parse("""[{"x":1},{"x":2}]""")
        assertEquals(listOf(1, 2), (ksonReaderOf(parameterType(0))!!(points) as List<*>).map { (it as Point).x })
        assertEquals(listOf(1, 2), (ksonReaderOf(parameterType(1))!!(points) as List<*>).map { (it as Point).x })

        val obj = Json.parse("""{"a":1}""")
        assertSame(obj, ksonReaderOf(JsonValue::class.java)!!(obj))
        assertSame(obj, ksonReaderOf(JsonObject::class.java)!!(obj))
        assertFailsWith<JsonTypeException> { ksonReaderOf(JsonArray::class.java)!!(obj) }
    }

    @Test
    fun findsEncodersForKsonInterfacesAndImplementations() {
        assertSame(PointJson, ksonEncoderOf(Point::class.java))
        assertSame(PointJson, ksonEncoderOf(PointImpl::class.java))
        assertNull(ksonEncoderOf(Plain::class.java))
    }

    @Test
    fun writesKsonInterfacesListsAndValues() {
        assertEquals(Json.parse("""{"x":3}"""), ksonWriterOf(Point::class.java)!!(PointImpl(3)))
        assertEquals(Json.parse("""{"x":3}"""), ksonWriterOf(PointImpl::class.java)!!(PointImpl(3)))
        assertEquals(JsonNull, ksonWriterOf(Point::class.java)!!(null))
        assertEquals(Json.parse("""[{"x":1},{"x":2}]"""), ksonWriterOf(parameterType(1))!!(listOf(PointImpl(1), PointImpl(2))))
        val obj = Json.parse("""{"a":1}""")
        assertSame(obj, ksonWriterOf(JsonObject::class.java)!!(obj))
        assertEquals(JsonNull, ksonWriterOf(JsonValue::class.java)!!(null))
    }

    @Test
    fun ignoresUnsupportedTypes() {
        assertNull(ksonReaderOf(String::class.java))
        assertNull(ksonReaderOf(Plain::class.java))
        assertNull(ksonReaderOf(parameterType(2)))
        assertNull(ksonWriterOf(String::class.java))
        assertNull(ksonWriterOf(parameterType(2)))
    }
}
