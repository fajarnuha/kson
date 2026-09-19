package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NumberTest {
    @Test
    fun equalityIsMathematical() {
        assertEquals(JsonNumber(1), JsonNumber(1L))
        assertEquals(JsonNumber(1), JsonNumber(1.0))
        assertEquals(JsonNumber.parse("1e2"), JsonNumber(100))
        assertEquals(JsonNumber.parse("0.1e1"), JsonNumber.parse("1"))
        assertEquals(JsonNumber.parse("-0"), JsonNumber.parse("0"))
        assertEquals(JsonNumber.parse("1.50"), JsonNumber.parse("1.5"))
        assertEquals(JsonNumber(1).hashCode(), JsonNumber(1.0).hashCode())
        assertNotEquals(JsonNumber(1), JsonNumber(2))
    }

    @Test
    fun literalIsPreserved() {
        val big = "12345678901234567890123"
        assertEquals(big, JsonNumber.parse(big).toJson())
        val precise = "0.1000000000000000055511151231257827"
        assertEquals(precise, Json.parse(precise).toJson())
        assertEquals("1E+2", Json.parse("1E+2").toJson())
    }

    @Test
    fun conversions() {
        assertEquals(3L, JsonNumber.parse("3").value)
        assertEquals(3L, JsonNumber.parse("3.0").value)
        assertEquals(300L, JsonNumber.parse("3e2").toLongOrNull())
        assertEquals(2.5, JsonNumber.parse("2.5").value)
        assertNull(JsonNumber.parse("2.5").toLongOrNull())
        assertNull(JsonNumber.parse("99999999999999999999").toLongOrNull())
        assertEquals(Long.MAX_VALUE, JsonNumber.parse(Long.MAX_VALUE.toString()).toLongOrNull())
        assertEquals(Long.MIN_VALUE, JsonNumber.parse(Long.MIN_VALUE.toString()).toLongOrNull())
        assertNull(JsonNumber.parse("3000000000").toIntOrNull())
        assertEquals(1.0E300, JsonNumber.parse("1e300").toDouble())
        assertNull(JsonNumber.parse("1e999999999999999999999").toLongOrNull())
        assertTrue(JsonNumber.parse("1e2").isIntegral)
        assertFalse(JsonNumber.parse("1.5").isIntegral)
    }

    @Test
    fun compare() {
        assertTrue(JsonNumber(1) < JsonNumber(2.5))
        assertTrue(JsonNumber.parse("1e3") > JsonNumber(999))
        assertEquals(0, JsonNumber(2).compareTo(JsonNumber.parse("2.0")))
    }

    @Test
    fun nonFiniteRejected() {
        assertFailsWith<IllegalArgumentException> { JsonNumber(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { JsonNumber(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { JsonNumber(Float.NEGATIVE_INFINITY) }
    }

    @Test
    fun literalValidation() {
        for (ok in listOf("0", "-0", "1", "-1.5", "1e10", "1E-10", "1.0e+3", "123.456")) {
            assertTrue(JsonNumber.isValidLiteral(ok), ok)
        }
        for (bad in listOf("", "-", "01", "1.", ".5", "+1", "1e", "1e+", "0x10", "NaN", "Infinity", "1 ")) {
            assertFalse(JsonNumber.isValidLiteral(bad), bad)
        }
        assertFailsWith<JsonException> { JsonNumber.parse("01") }
    }
}
