package com.fajarnuha.kson.ksp

import com.fajarnuha.kson.Json
import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.Kson
import com.fajarnuha.kson.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Kson
interface Settings {
    val id: Long
    val theme: Theme get() = Theme.LIGHT
    val label: String get() = "settings-$id"
    val retries: Int get() = 3
    val nickname: String? get() = "anon"
    val tags: List<String> get() = emptyList()
    val limits: Limits get() = DefaultLimits

    interface Limits {
        val max: Int
        val min: Int get() = 0
    }

    enum class Theme { LIGHT, DARK }
}

private object DefaultLimits : Settings.Limits {
    override val max = 10
}

class DefaultsTest {
    @Test
    fun builderUsesInterfaceDefaults() {
        val settings = settingsKson { id = 7 }
        assertEquals(Settings.Theme.LIGHT, settings.theme)
        assertEquals("settings-7", settings.label)
        assertEquals(3, settings.retries)
        assertEquals("anon", settings.nickname)
        assertEquals(emptyList(), settings.tags)
        assertEquals(10, settings.limits.max)
    }

    @Test
    fun defaultsReadPropertiesSetLaterInTheBlock() {
        val settings = settingsKson {
            assertEquals(3, retries)
            id = 1
            assertEquals("settings-1", label)
            id = 2
        }
        assertEquals("settings-2", settings.label)
    }

    @Test
    fun assignedValuesReplaceDefaults() {
        val settings = settingsKson {
            id = 1
            theme = Settings.Theme.DARK
            label = "custom"
            nickname = null
            limits { max = 5 }
        }
        assertEquals(Settings.Theme.DARK, settings.theme)
        assertEquals("custom", settings.label)
        assertNull(settings.nickname)
        assertEquals(5, settings.limits.max)
        assertEquals(0, settings.limits.min)
    }

    @Test
    fun decoderUsesDefaultsForMissingKeys() {
        val settings = SettingsJson.decode("""{"id":7,"limits":{"max":2}}""")
        assertEquals("settings-7", settings.label)
        assertEquals("anon", settings.nickname)
        assertEquals(0, settings.limits.min)
        assertEquals(
            Json.parse(
                """{"id":7,"theme":"LIGHT","label":"settings-7","retries":3,"nickname":"anon","tags":[],
                   "limits":{"max":2,"min":0}}""",
            ),
            SettingsJson.encode(settings),
        )
    }

    @Test
    fun decoderKeepsExplicitNullOnlyForNullableProperties() {
        val settings = SettingsJson.decode("""{"id":7,"nickname":null,"retries":null,"label":"given"}""")
        assertNull(settings.nickname)
        assertEquals(3, settings.retries)
        assertEquals("given", settings.label)
    }

    @Test
    fun schemaDoesNotRequireDefaultedProperties() {
        assertEquals(JsonArray(listOf(JsonString("id"))), SettingsJson.schema.jsonObject["required"])
        val limits = SettingsJson.schema.jsonObject["properties"]!!.jsonObject["limits"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonString("max"))), limits["required"])
    }
}
