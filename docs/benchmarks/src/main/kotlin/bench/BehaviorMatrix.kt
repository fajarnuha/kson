package bench

import com.fajarnuha.kson.JsonException
import com.fajarnuha.kson.withoutNulls
import com.fasterxml.jackson.core.JacksonException
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.squareup.moshi.JsonDataException
import kotlinx.serialization.SerializationException
import probe.GsonInner
import probe.GsonProbe
import probe.JacksonProbe
import probe.KotlinxInner
import probe.KotlinxProbe
import probe.KsonProbeJson
import probe.MoshiInner
import probe.MoshiProbe
import probe.ProbeRole
import probe.ksonProbeKson
import java.io.File
import java.io.IOException
import com.fajarnuha.kson.Json as KsonJson

/**
 * Runs the same inputs through every library's default configuration and writes the outcomes to
 * `results/behavior.md`. Every case runs on a thread with a 1 MB stack (a typical worker-thread size).
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "results").apply { mkdirs() }
    var report = ""
    val thread = Thread(null, { report = buildReport() }, "behavior", 1L shl 20)
    thread.start()
    thread.join()
    File(out, "behavior.md").writeText(report)
    println(report)
}

/** A library-neutral view of a decoded probe. Fields are nullable so a null in a non-null property shows up. */
private data class View(
    val id: Int?, val name: String?, val score: Double?, val role: ProbeRole?,
    val nickname: String?, val tags: List<String>?, val inner: Long?,
) {
    val hasIllegalNull: Boolean get() = id == null || name == null || score == null || role == null || tags == null || inner == null
}

private class EncodeInput(val name: String = "Ann", val score: Double = 1.5, val nickname: String? = null)

private class Library(
    val name: String,
    val expected: List<Class<out Throwable>>,
    val decode: (String) -> View?,
    val encode: (EncodeInput) -> String,
    /** Parses into the library's untyped tree and writes it back out. */
    val tree: (String) -> String,
    val roundTrip: (String) -> String,
)

private val libraries = listOf(
    Library(
        "kson",
        listOf(JsonException::class.java),
        decode = { text -> KsonProbeJson.decode(text).let { View(it.id, it.name, it.score, it.role, it.nickname, it.tags, it.inner.value) } },
        encode = { input ->
            KsonProbeJson.encode(
                ksonProbeKson {
                    id = 1; name = input.name; score = input.score; role = ProbeRole.ADMIN; nickname = input.nickname
                    inner { value = 7 }
                },
            ).toJson()
        },
        tree = { KsonJson.parse(it).toJson() },
        roundTrip = { bench.kson.UserPageJson.encode(bench.kson.UserPageJson.decode(it)).toJson() },
    ),
    Library(
        "kotlinx.serialization",
        listOf(SerializationException::class.java),
        decode = { text ->
            Codecs.kotlinx.decodeFromString(KotlinxProbe.serializer(), text)
                .let { View(it.id, it.name, it.score, it.role, it.nickname, it.tags, it.inner.value) }
        },
        encode = { input ->
            Codecs.kotlinx.encodeToString(
                KotlinxProbe.serializer(),
                KotlinxProbe(1, input.name, input.score, ProbeRole.ADMIN, input.nickname, inner = KotlinxInner(7)),
            )
        },
        tree = { Codecs.kotlinx.parseToJsonElement(it).toString() },
        roundTrip = {
            val page = Codecs.kotlinx.decodeFromString(bench.kotlinx.UserPage.serializer(), it)
            Codecs.kotlinx.encodeToString(bench.kotlinx.UserPage.serializer(), page)
        },
    ),
    Library(
        "moshi",
        listOf(JsonDataException::class.java, IOException::class.java),
        decode = { text ->
            Codecs.moshi.adapter(MoshiProbe::class.java).fromJson(text)
                ?.let { View(it.id, it.name, it.score, it.role, it.nickname, it.tags, it.inner.value) }
        },
        encode = { input ->
            Codecs.moshi.adapter(MoshiProbe::class.java)
                .toJson(MoshiProbe(1, input.name, input.score, ProbeRole.ADMIN, input.nickname, inner = MoshiInner(7)))
        },
        tree = { Codecs.moshiTree.toJson(Codecs.moshiTree.fromJson(it)) },
        roundTrip = { Codecs.moshiPage.toJson(Codecs.moshiPage.fromJson(it)) },
    ),
    Library(
        "gson",
        listOf(JsonParseException::class.java),
        decode = { text ->
            // Gson may leave null in non-null Kotlin properties, so read them as nullable.
            (Codecs.gson.fromJson(text, GsonProbe::class.java) as GsonProbe?)?.let {
                @Suppress("USELESS_CAST")
                View(it.id, it.name as String?, it.score, it.role as ProbeRole?, it.nickname, it.tags as List<String>?, (it.inner as GsonInner?)?.value)
            }
        },
        encode = { input ->
            Codecs.gson.toJson(GsonProbe(1, input.name, input.score, ProbeRole.ADMIN, input.nickname, inner = GsonInner(7)))
        },
        tree = { JsonParser.parseString(it).toString() },
        roundTrip = { Codecs.gson.toJson(Codecs.gson.fromJson(it, bench.gson.UserPage::class.java)) },
    ),
    Library(
        "jackson",
        listOf(JacksonException::class.java),
        decode = { text ->
            (Codecs.jackson.readValue(text, JacksonProbe::class.java) as JacksonProbe?)
                ?.let { View(it.id, it.name, it.score, it.role, it.nickname, it.tags, it.inner.value) }
        },
        encode = { input ->
            Codecs.jackson.writeValueAsString(
                JacksonProbe(1, input.name, input.score, ProbeRole.ADMIN, input.nickname, inner = probe.JacksonInner(7)),
            )
        },
        tree = { Codecs.jackson.writeValueAsString(Codecs.jackson.readTree(it)) },
        roundTrip = { Codecs.jackson.writeValueAsString(Codecs.jackson.readValue(it, bench.jackson.UserPage::class.java)) },
    ),
)

/** Builds a probe object. Each argument is raw JSON text for that field, or null to leave the key out. */
private fun probe(
    id: String? = "1",
    name: String? = "\"Ann\"",
    score: String? = "1.5",
    role: String? = "\"ADMIN\"",
    nickname: String? = "null",
    tags: String? = "[\"x\"]",
    inner: String? = "{\"value\":7}",
    extra: String = "",
): String = listOfNotNull(
    id?.let { "\"id\":$it" },
    name?.let { "\"name\":$it" },
    score?.let { "\"score\":$it" },
    role?.let { "\"role\":$it" },
    nickname?.let { "\"nickname\":$it" },
    tags?.let { "\"tags\":$it" },
    inner?.let { "\"inner\":$it" },
).joinToString(",", "{", "$extra}")

/** [lossy] flags a successful decode that silently changed or invented data. */
private class Case(
    val group: String,
    val title: String,
    val input: String,
    val lossy: (View) -> Boolean = { false },
    val show: (View) -> String,
)

private val baseline = probe()

private val decodeCases = listOf(
    Case("Binding", "Valid input", baseline) { "id=${it.id}, tags=${it.tags}" },
    Case("Binding", "Missing required `name`", probe(name = null)) { "name=${it.name}" },
    Case("Binding", "`\"name\": null` for a non-null property", probe(name = "null")) { "name=${it.name}" },
    Case("Binding", "Missing optional `nickname` and defaulted `tags`", probe(nickname = null, tags = null)) { "nickname=${it.nickname}, tags=${it.tags}" },
    Case("Binding", "`\"tags\": null` for a defaulted non-null property", probe(tags = "null")) { "tags=${it.tags}" },
    Case("Binding", "Unknown key `\"extra\": 1`", probe(extra = ",\"extra\":1")) { "id=${it.id}" },
    Case("Binding", "Missing nested `inner.value`", probe(inner = "{}"), lossy = { it.inner == 0L }) { "inner.value=${it.inner}" },
    Case("Binding", "`\"inner\": null`", probe(inner = "null")) { "inner=${it.inner}" },
    Case("Binding", "Top-level `null`", "null") { it.toString() },
    Case("Binding", "Top-level array for an object type", "[]") { it.toString() },
    Case("Types", "`\"id\": \"42\"` (string for Int)", probe(id = "\"42\"")) { "id=${it.id}" },
    Case("Types", "`\"id\": 1.0`", probe(id = "1.0")) { "id=${it.id}" },
    Case("Types", "`\"id\": 1.5`", probe(id = "1.5"), lossy = { true }) { "id=${it.id}" },
    Case("Types", "`\"id\": 3000000000` (Int overflow)", probe(id = "3000000000")) { "id=${it.id}" },
    Case("Types", "`\"score\": \"1.5\"` (string for Double)", probe(score = "\"1.5\"")) { "score=${it.score}" },
    Case("Types", "`\"score\": 1e400` (Double overflow)", probe(score = "1e400"), lossy = { it.score?.isInfinite() == true }) { "score=${it.score}" },
    Case("Types", "`\"name\": 42` (number for String)", probe(name = "42")) { "name=${it.name}" },
    Case("Types", "`\"tags\": \"x\"` (string for List)", probe(tags = "\"x\"")) { "tags=${it.tags}" },
    Case("Types", "`\"role\": \"OWNER\"` (unknown enum)", probe(role = "\"OWNER\"")) { "role=${it.role}" },
    Case("Types", "`\"role\": \"admin\"` (enum case differs)", probe(role = "\"admin\"")) { "role=${it.role}" },
    Case("Syntax", "Trailing comma", probe(extra = ",")) { "id=${it.id}" },
    Case("Syntax", "`// comment`", "{\"id\":1, // c\n" + baseline.drop(8)) { "id=${it.id}" },
    Case("Syntax", "Single-quoted strings", probe(name = "'Ann'")) { "name=${it.name}" },
    Case("Syntax", "Unquoted key", baseline.replaceFirst("\"id\"", "id")) { "id=${it.id}" },
    Case("Syntax", "`NaN` literal", probe(score = "NaN")) { "score=${it.score}" },
    Case("Syntax", "Leading zero `01`", probe(id = "01")) { "id=${it.id}" },
    Case("Syntax", "Invalid escape `\\x`", probe(name = "\"a\\x\"")) { "name=${it.name}" },
    Case("Syntax", "Raw newline inside a string", probe(name = "\"a\nb\"")) { "name=${it.name?.replace("\n", "\\n")}" },
    Case("Syntax", "Lone surrogate `\\ud800`", probe(name = "\"\\ud800\"")) { "name length=${it.name?.length}" },
    Case("Syntax", "Duplicate key `\"id\":1,\"id\":2`", probe(extra = ",\"id\":2")) { "id=${it.id}" },
    Case("Syntax", "Leading byte-order mark", "\uFEFF" + baseline) { "id=${it.id}" },
    Case("Syntax", "Trailing garbage after the value", "$baseline x") { "id=${it.id}" },
    Case("Syntax", "Truncated input", baseline.take(20)) { "id=${it.id}" },
    Case("Syntax", "Empty input", "") { it.toString() },
)

private class EncodeCase(val title: String, val input: EncodeInput)

private val encodeCases = listOf(
    EncodeCase("`nickname = null`, `tags` left at its default", EncodeInput()),
    EncodeCase("`name = \"<b>Tom & 'Jerry'</b> = \\u2028\"`", EncodeInput(name = "<b>Tom & 'Jerry'</b> = \u2028")),
    EncodeCase("`score = 1.0`", EncodeInput(score = 1.0)),
    EncodeCase("`score = Double.NaN`", EncodeInput(score = Double.NaN)),
)

private fun Library.describe(error: Throwable): String {
    val unexpected = expected.none { it.isInstance(error) }
    return "✗ `${error.javaClass.simpleName}`" + if (unexpected) " ⚠" else ""
}

private fun message(error: Throwable): String =
    (error.message ?: "").replace("\n", " ").replace("|", "\\|").let { if (it.length > 220) it.take(220) + "…" else it }

private fun buildReport(): String = buildString {
    appendLine("<!-- Generated by `./gradlew -p docs/benchmarks behavior`; do not edit by hand. -->")
    appendLine()
    appendLine("Environment: JDK ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}), ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.")
    appendLine("Each library runs in its default configuration. ✓ = decoded, ✗ = threw (exception class shown),")
    appendLine("⚠ = a surprising result: a null inside a non-null Kotlin property, silently lost or invented data, or an exception outside the library's documented")
    appendLine("exception type (kson `JsonException`, kotlinx `SerializationException`, Moshi `JsonDataException`/`IOException`,")
    appendLine("Gson `JsonParseException`, Jackson `JacksonException`).")
    appendLine()

    appendLine("### Data equivalence")
    appendLine()
    appendLine("A 1,000-user page decoded and re-encoded by each library, compared with the input as JSON values (numbers by value, nulls dropped).")
    appendLine()
    appendLine("| Library | Round trip equals input |")
    appendLine("|---|---|")
    val page = Payloads.page(1000)
    val expected = KsonJson.parse(page).withoutNulls()
    libraries.forEach { lib ->
        val result = runCatching { KsonJson.parse(lib.roundTrip(page)).withoutNulls() == expected }
        appendLine("| ${lib.name} | ${result.fold({ if (it) "yes" else "**no**" }, { "error: ${message(it)}" })} |")
    }
    appendLine()

    val details = mutableListOf<String>()
    val header = "| Case | " + libraries.joinToString(" | ") { it.name } + " |"
    val divider = "|---|" + libraries.joinToString("") { "---|" }
    decodeCases.groupBy { it.group }.forEach { (group, cases) ->
        appendLine("### Decoding: $group")
        appendLine()
        appendLine(header)
        appendLine(divider)
        cases.forEach { case ->
            val cells = libraries.map { lib ->
                try {
                    val view = lib.decode(case.input)
                    when {
                        view == null -> "✓ returned `null` ⚠"
                        view.hasIllegalNull || case.lossy(view) -> "✓ ${case.show(view)} ⚠"
                        else -> "✓ ${case.show(view)}"
                    }
                } catch (error: Throwable) {
                    details += "| ${case.title} | ${lib.name} | `${error.javaClass.name}` | ${message(error)} |"
                    lib.describe(error)
                }
            }
            appendLine("| ${case.title} | ${cells.joinToString(" | ")} |")
        }
        appendLine()
    }

    appendLine("### Nesting depth")
    appendLine()
    appendLine("Untyped tree parse of 100,000 nested arrays (`[[[…]]]`) on a 1 MB stack.")
    appendLine()
    appendLine("| Library | Outcome |")
    appendLine("|---|---|")
    val deep = "[".repeat(100_000) + "]".repeat(100_000)
    libraries.forEach { lib ->
        val cell = try {
            lib.tree(deep)
            "✓ parsed"
        } catch (error: Throwable) {
            details += "| Nesting 100,000 deep | ${lib.name} | `${error.javaClass.name}` | ${message(error)} |"
            lib.describe(error)
        }
        appendLine("| ${lib.name} | $cell |")
    }
    appendLine()

    appendLine("### Untyped tree number round trip")
    appendLine()
    appendLine("Parse into the untyped tree, then write it back with the same library.")
    appendLine()
    appendLine(header)
    appendLine(divider)
    listOf(
        "`12345678901234567890` (above Long.MAX_VALUE)" to "[12345678901234567890]",
        "`0.1000000000000000055511151231257827`" to "[0.1000000000000000055511151231257827]",
        "`1e3`, `1.50`, `-0`" to "[1e3,1.50,-0]",
    ).forEach { (title, input) ->
        val cells = libraries.map { lib ->
            try {
                "`" + lib.tree(input) + "`"
            } catch (error: Throwable) {
                details += "| Tree $title | ${lib.name} | `${error.javaClass.name}` | ${message(error)} |"
                lib.describe(error)
            }
        }
        appendLine("| $title | ${cells.joinToString(" | ")} |")
    }
    appendLine()

    appendLine("### Encoding")
    appendLine()
    appendLine(header)
    appendLine(divider)
    encodeCases.forEach { case ->
        val cells = libraries.map { lib ->
            try {
                "`" + lib.encode(case.input).replace("|", "\\|").replace("\u2028", "\\u2028(raw)") + "`"
            } catch (error: Throwable) {
                details += "| Encode ${case.title} | ${lib.name} | `${error.javaClass.name}` | ${message(error)} |"
                lib.describe(error)
            }
        }
        appendLine("| ${case.title} | ${cells.joinToString(" | ")} |")
    }
    appendLine()

    appendLine("### Error messages")
    appendLine()
    appendLine("| Case | Library | Exception | Message |")
    appendLine("|---|---|---|---|")
    details.forEach(::appendLine)
}
