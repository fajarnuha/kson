package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaTest {
    private fun schemaOf(v: JsonValue, o: JsonSchemaOptions = JsonSchemaOptions(schemaUri = null)) = v.toJsonSchemaValue(o)

    @Test
    fun objectSchemaIsString() {
        val schema = json {
            "id" to 42
            "name" to "Fajar"
            "score" to 9.5
            "active" to true
            "nickname" to null
        }.toJsonSchema(JsonSchemaOptions(title = "User"))

        val expected = """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "User",
              "type": "object",
              "properties": {
                "id": {
                  "type": "integer"
                },
                "name": {
                  "type": "string"
                },
                "score": {
                  "type": "number"
                },
                "active": {
                  "type": "boolean"
                },
                "nickname": {
                  "type": "null"
                }
              },
              "required": [
                "id",
                "name",
                "score",
                "active",
                "nickname"
              ]
            }
        """.trimIndent()
        assertEquals(expected, schema)
        // The schema string is itself valid JSON.
        assertEquals("object", Json.parse(schema)["type"]?.string)
    }

    @Test
    fun arraysOfObjectsMergeAndIntersectRequired() {
        val s = schemaOf(Json.parse("""[{"a":1,"b":"x"},{"a":2.5,"c":true}]"""))
        assertEquals(
            """{"type":"array","items":{"type":"object","properties":{"a":{"type":["integer","number"]},""" +
                """"b":{"type":"string"},"c":{"type":"boolean"}},"required":["a"]}}""",
            s.toJson(),
        )
    }

    @Test
    fun heterogeneousAndEmptyArrays() {
        assertEquals("""{"type":"array","items":{"type":["integer","string","null"]}}""", schemaOf(jsonArray(1, "a", null)).toJson())
        assertEquals("""{"type":"array"}""", schemaOf(jsonArray()).toJson())
        assertEquals("""{"type":"object"}""", schemaOf(JsonObject.Empty).toJson())
    }

    @Test
    fun nestedArrays() {
        assertEquals(
            """{"type":"array","items":{"type":"array","items":{"type":"integer"}}}""",
            schemaOf(Json.parse("[[1,2],[3]]")).toJson(),
        )
    }

    @Test
    fun formats() {
        val s = schemaOf(
            json {
                "at" to "2026-09-19T10:00:00Z"
                "day" to "2026-09-19"
                "t" to "10:00:00"
                "id" to "123e4567-e89b-12d3-a456-426614174000"
                "mail" to "me@example.com"
                "ip" to "192.168.1.1"
                "site" to "https://example.com/x"
                "plain" to "hello"
            },
        )
        val formats = s["properties"]!!.jsonObject.mapValues { it.value["format"]?.string }
        assertEquals(
            mapOf(
                "at" to "date-time", "day" to "date", "t" to "time", "id" to "uuid",
                "mail" to "email", "ip" to "ipv4", "site" to "uri", "plain" to null,
            ),
            formats,
        )
        assertNull(schemaOf(JsonString("2026-09-19"), JsonSchemaOptions(schemaUri = null, detectFormats = false))["format"])
    }

    @Test
    fun formatDroppedWhenArrayElementsDisagree() {
        assertEquals(
            """{"type":"array","items":{"type":"string"}}""",
            schemaOf(jsonArray("2026-09-19", "hello")).toJson(),
        )
        assertEquals(
            """{"type":"array","items":{"type":"string","format":"date"}}""",
            schemaOf(jsonArray("2026-09-19", "2026-01-01")).toJson(),
        )
        assertEquals(
            """{"type":"array","items":{"type":["string","integer"]}}""",
            schemaOf(jsonArray("hello", 1, "2026-01-01")).toJson(),
        )
    }

    @Test
    fun optionsForRequiredAndAdditionalProperties() {
        val s = schemaOf(
            json { "a" to 1 },
            JsonSchemaOptions(schemaUri = null, requireAllProperties = false, additionalProperties = false, description = "d"),
        )
        assertEquals("""{"description":"d","type":"object","properties":{"a":{"type":"integer"}},"additionalProperties":false}""", s.toJson())
    }

    @Test
    fun inferFromText() {
        val s = inferJsonSchema("""{"a":[1]}""", JsonSchemaOptions(schemaUri = null, pretty = false))
        assertEquals("""{"type":"object","properties":{"a":{"type":"array","items":{"type":"integer"}}},"required":["a"]}""", s)
    }
}
