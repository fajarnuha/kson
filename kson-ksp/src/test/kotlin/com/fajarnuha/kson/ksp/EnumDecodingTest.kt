package com.fajarnuha.kson.ksp

import com.fajarnuha.kson.JsonTypeException
import com.fajarnuha.kson.Kson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Kson
interface Swatch {
    val color: Everything.Color
}

class EnumDecodingTest {
    @Test
    fun decodesKnownConstants() {
        assertEquals(Everything.Color.GREEN, SwatchJson.decode("""{"color":"GREEN"}""").color)
    }

    @Test
    fun rejectsUnknownConstantsWithJsonTypeException() {
        val error = assertFailsWith<JsonTypeException> { SwatchJson.decode("""{"color":"BLUE"}""") }
        assertEquals("Expected one of [RED, GREEN] but was \"BLUE\"", error.message)
        assertFailsWith<JsonTypeException> { SwatchJson.decode("""{"color":"green"}""") }
        assertFailsWith<JsonTypeException> { SwatchJson.decode("""{"color":1}""") }
    }
}
