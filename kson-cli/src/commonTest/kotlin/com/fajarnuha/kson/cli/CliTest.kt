package com.fajarnuha.kson.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, stdin: String = "", files: Map<String, String> = emptyMap()): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val io = CliIo(
            readFile = { files[it] ?: throw UsageException("Cannot open file: $it") },
            readStdin = { stdin },
            out = { out.append(it).append('\n') },
            err = { err.append(it).append('\n') },
        )
        val code = runCli(args.toList(), io)
        return Run(code, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
    }

    private val doc = """{"name":"kson","tags":["a","b"],"meta":{"v":1,"ok":true}}"""

    @Test
    fun fmtAndMin() {
        assertEquals("{\n  \"a\": [\n    1\n  ]\n}", run("fmt", stdin = """{"a":[1]}""").out)
        assertEquals("""{"a":[1]}""", run("min", stdin = "{ \"a\" : [ 1 ] }").out)
        assertEquals("{\n    \"a\": 1\n}", run("fmt", "-i", "4", stdin = """{"a":1}""").out)
        assertEquals("{\n\t\"a\": 1\n}", run("fmt", "--tab", stdin = """{"a":1}""").out)
        assertEquals("""{"a":1,"b":2}""", run("fmt", "-c", "-s", stdin = """{"b":2,"a":1}""").out)
        assertEquals("\"\\u00e9\"", run("min", "--ascii", stdin = "\"é\"").out)
        assertEquals("[1]", run("min", "in.json", files = mapOf("in.json" to "[ 1 ]")).out)
        assertEquals("[1]", run("min", "-", stdin = "[ 1 ]").out)
    }

    @Test
    fun validate() {
        assertEquals(0, run("validate", stdin = "[1]").code)
        val bad = run("validate", stdin = "{\n  \"a\": 01\n}")
        assertEquals(1, bad.code)
        assertTrue(bad.err.startsWith("<stdin>:2:"), bad.err)
        assertTrue("line" !in bad.err, bad.err)
        val quiet = run("validate", "-q", stdin = "[")
        assertEquals(1, quiet.code)
        assertEquals("", quiet.err)
        assertEquals(1, run("validate", "--strict", stdin = """{"a":1,"a":2}""").code)
        assertEquals(0, run("validate", stdin = """{"a":1,"a":2}""").code)
    }

    @Test
    fun invalidInputOnOtherCommandsExitsWithOne() {
        val r = run("fmt", stdin = "{nope}")
        assertEquals(1, r.code)
        assertTrue(r.err.startsWith("kson: invalid JSON"), r.err)
    }

    @Test
    fun getKeysTypePaths() {
        assertEquals("\"kson\"", run("get", "/name", stdin = doc).out)
        assertEquals("kson", run("get", "-r", "/name", stdin = doc).out)
        assertEquals("\"b\"", run("get", "/tags/1", stdin = doc).out)
        assertEquals("""{"v":1,"ok":true}""", run("get", "-c", "/meta", stdin = doc).out)
        assertEquals("1", run("get", "/meta/v", "f.json", files = mapOf("f.json" to doc)).out)
        assertEquals(1, run("get", "/nope", stdin = doc).code)
        assertEquals(2, run("get", stdin = doc).code)

        assertEquals("name\ntags\nmeta", run("keys", stdin = doc).out)
        assertEquals("0\n1", run("keys", "/tags", stdin = doc).out)
        assertEquals("v\nok", run("keys", "/meta", "f.json", files = mapOf("f.json" to doc)).out)
        assertEquals("name\ntags\nmeta", run("keys", "f.json", files = mapOf("f.json" to doc)).out)
        assertEquals(1, run("keys", "/name", stdin = doc).code)

        assertEquals("object", run("type", stdin = doc).out)
        assertEquals("boolean", run("type", "/meta/ok", stdin = doc).out)

        assertEquals("/name\n/tags\n/tags/0\n/tags/1\n/meta\n/meta/v\n/meta/ok", run("paths", stdin = doc).out)
    }

    @Test
    fun schema() {
        val r = run("schema", "-c", "--title", "T", "--closed", stdin = """{"a":[1],"at":"2026-01-01"}""")
        assertEquals(
            """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","title":"T","type":"object",""" +
                """"properties":{"a":{"type":"array","items":{"type":"integer"}},"at":{"type":"string","format":"date"}},""" +
                """"required":["a","at"],"additionalProperties":false}""",
            r.out,
        )
        val plain = run("schema", "-c", "--no-formats", "--no-required", stdin = """{"at":"2026-01-01"}""").out
        assertTrue("format" !in plain && "required" !in plain, plain)
    }

    @Test
    fun merge() {
        val files = mapOf("a.json" to """{"x":{"y":1},"k":1}""", "b.json" to """{"x":{"z":2},"k":2}""")
        assertEquals("""{"x":{"y":1,"z":2},"k":2}""", run("merge", "-c", "a.json", "b.json", files = files).out)
        assertEquals("""{"x":{"y":1},"k":3}""", run("merge", "-c", "a.json", "-", files = files, stdin = """{"k":3}""").out)
        assertEquals(2, run("merge", "a.json", files = files).code)
        assertEquals(2, run("merge", "a.json", "c.json", files = files + ("c.json" to "[1]")).code)
    }

    @Test
    fun build() {
        assertEquals(
            """{"name":"Fajar","age":42,"ok":true,"tags":["a"],"address":{"city":"Tangsel","geo":{"lat":-6.29}},"a.b":"dot","eq":"x=y"}""",
            run(
                "build", "-c", "name=Fajar", "age:=42", "ok:=true", "tags:=[\"a\"]",
                "address.city=Tangsel", "address.geo.lat:=-6.29", "a\\.b=dot", "eq=x=y",
            ).out,
        )
        assertEquals("""["x",1,{"k":null}]""", run("build", "-a", "-c", "x", ":=1", ":={\"k\":null}").out)
        assertEquals("""["-x"]""", run("build", "-a", "-c", "--", "-x").out)
        assertEquals("{}", run("build").out)
        assertEquals(2, run("build", "novalue").code)
        assertEquals(2, run("build", "n:=nope").code)
        assertEquals(2, run("build", "a..b=1").code)
    }

    @Test
    fun usageErrors() {
        assertEquals(2, run().code)
        assertEquals(2, run("frobnicate").code)
        assertEquals(2, run("fmt", "--bogus", stdin = "1").code)
        assertEquals(2, run("fmt", "-i", "x", stdin = "1").code)
        assertEquals(2, run("fmt", "a", "b").code)
        assertEquals(2, run("fmt", "missing.json").code)
        assertEquals(0, run("help").code)
        assertTrue(run("--help").out.startsWith("kson "))
        assertEquals("kson $VERSION", run("version").out)
    }
}
