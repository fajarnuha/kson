package com.fajarnuha.kson.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun run(
        vararg args: String,
        stdin: String = "",
        files: Map<String, String> = emptyMap(),
        tty: Boolean = false,
        stdinTty: Boolean = false,
        env: Map<String, String> = emptyMap(),
    ): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        var stdinReads = 0
        val io = CliIo(
            readFile = { files[it] ?: throw UsageException("Cannot open file: $it") },
            readStdin = {
                stdinReads++
                stdin
            },
            out = { out.append(it) },
            err = { err.append(it).append('\n') },
            stdoutIsTerminal = tty,
            stdinIsTerminal = stdinTty,
            getenv = { env[it] },
            environment = { env },
        )
        val code = runCli(args.toList(), io)
        return Run(code, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
    }

    private val doc = """{"name":"kson","tags":["a","b"],"meta":{"v":1,"ok":true}}"""
    private val esc = "\u001B["

    // --- pipelining: `cat a.json | kson` ------------------------------------------------

    @Test
    fun bareInvocationPrettyPrintsStdin() {
        assertEquals("{\n  \"a\": [\n    1\n  ]\n}", run(stdin = """{"a":[1]}""").out)
    }

    @Test
    fun colorsOnlyWhenStdoutIsATerminal() {
        val colored = run(stdin = """{"a":"x"}""", tty = true).out
        assertTrue(colored.startsWith("${esc}1;39m{"), colored)
        assertTrue("${esc}34;1m\"a\"" in colored && "${esc}0;32m\"x\"" in colored, colored)
        assertEquals("{\n  \"a\": \"x\"\n}", run(stdin = """{"a":"x"}""", tty = false).out)
        assertEquals("[1]", run("-c", stdin = "[1]", tty = true, env = mapOf("NO_COLOR" to "1")).out)
        assertEquals("[1]", run("-M", "-c", stdin = "[1]", tty = true).out)
        assertEquals("[1]", run("-c", stdin = "[1]", tty = true, env = mapOf("TERM" to "dumb")).out)
        assertTrue(run("-C", "-c", stdin = "[1]", tty = false).out.startsWith(esc), "-C forces color")
        assertEquals("${esc}0;35m\"x\"${esc}0m", run(".", stdin = "\"x\"", tty = true, env = mapOf("JQ_COLORS" to "::::0;35")).out)
        assertEquals("x", run("-r", ".", stdin = "\"x\"", tty = true).out, "raw strings are never colored")
    }

    @Test
    fun bareInvocationOnATerminalShowsUsage() {
        val r = run(stdinTty = true)
        assertEquals(2, r.code)
        assertTrue(r.err.startsWith("kson "), r.err)
    }

    // --- jq-style queries ----------------------------------------------------------------

    @Test
    fun filterIsTheDefaultCommand() {
        assertEquals("\"kson\"", run(".name", stdin = doc).out)
        assertEquals("kson", run("-r", ".name", stdin = doc).out)
        assertEquals("a\nb", run("-r", ".tags[]", stdin = doc).out)
        assertEquals("ab", run("-j", ".tags[]", stdin = doc).out)
        assertEquals("[\"a\",\"b\"]", run("-c", ".tags", stdin = doc).out)
        assertEquals("\"kson\"", run("query", ".name", stdin = doc).out)
        assertEquals("\"kson\"", run("q", ".name", "f.json", files = mapOf("f.json" to doc)).out)
        assertEquals("[\"b\",\"a\"]", run("-rc", ".tags | reverse", stdin = doc).out, "combined short flags")
        assertEquals("-3", run("-(.a)", stdin = """{"a":3}""").out, "filters may start with '-'")
    }

    @Test
    fun realisticPipelines() {
        val users = """{"users":[{"name":"ann","age":40,"tags":["x"]},{"name":"bob","age":20,"tags":[]}]}"""
        assertEquals("ann", run("-r", ".users[] | select(.age > 30) | .name", stdin = users).out)
        assertEquals("""[{"name":"ann","n":1},{"name":"bob","n":0}]""", run("-c", "[.users[] | {name, n: (.tags | length)}]", stdin = users).out)
        assertEquals("60", run(".users | map(.age) | add", stdin = users).out)
        assertEquals("ann,40\nbob,20", run("-r", ".users[] | [.name, .age] | @csv", stdin = users).out.replace("\"", ""))
        assertEquals("""{"users":[{"name":"ann","age":41,"tags":["x"]},{"name":"bob","age":21,"tags":[]}]}""", run("-c", ".users[].age += 1", stdin = users).out)
    }

    @Test
    fun streamsOfValuesAndFiles() {
        assertEquals("1\n2\n3", run(".a", stdin = "{\"a\":1}\n{\"a\":2} {\"a\":3}").out)
        assertEquals("[1,2]", run("-c", "-s", "map(.a)", stdin = """{"a":1} {"a":2}""").out)
        assertEquals("1\n2", run(".", "a.json", "b.json", files = mapOf("a.json" to "1", "b.json" to "2")).out)
        assertEquals("6", run("-n", "reduce inputs as \$x (0; . + \$x)", stdin = "1 2 3").out)
        assertEquals("2", run("-n", "1 + 1").out)
        assertEquals("\"line1\"\n\"line2\"", run("-R", ".", stdin = "line1\nline2\n").out)
        assertEquals("\"a\\nb\\n\"", run("-R", "-s", ".", stdin = "a\nb\n").out)
    }

    @Test
    fun argumentsAndEnvironment() {
        assertEquals("\"5\"", run("-n", "--arg", "v", "5", "\$v").out)
        assertEquals("6", run("-n", "--argjson", "v", "5", "\$v + 1").out)
        assertEquals("{\"v\":\"x\"}", run("-nc", "--arg", "v", "x", "\$ARGS.named").out)
        assertEquals("\"/home/me\"", run("-n", "\$ENV.HOME", env = mapOf("HOME" to "/home/me")).out)
        assertEquals("\"/home/me\"", run("-n", "env.HOME", env = mapOf("HOME" to "/home/me")).out)
        assertEquals(2, run("-n", "--argjson", "v", "{bad", "\$v").code)
        assertEquals(2, run("-n", "--arg", "v").code)
    }

    @Test
    fun outputFormatting() {
        assertEquals("{\"a\":1,\"b\":2}", run("-cS", ".", stdin = """{"b":2,"a":1}""").out)
        assertEquals("\"\\u00e9\"", run("-a", ".", stdin = "\"é\"").out)
        assertEquals("{\n    \"a\": 1\n}", run("--indent", "4", ".", stdin = """{"a":1}""").out)
        assertEquals("{\n\t\"a\": 1\n}", run("--tab", ".", stdin = """{"a":1}""").out)
    }

    @Test
    fun exitCodesFollowJq() {
        assertEquals(0, run(".", stdin = "1").code)
        assertEquals(1, run("-e", ".a", stdin = """{"a":false}""").code)
        assertEquals(1, run("-e", ".a", stdin = """{"a":null}""").code)
        assertEquals(0, run("-e", ".a", stdin = """{"a":0}""").code)
        assertEquals(4, run("-e", "empty", stdin = "1").code)

        val compile = run(".a |", stdin = "1")
        assertEquals(3, compile.code)
        assertTrue("compile error" in compile.err, compile.err)
        assertEquals(3, run("nosuchfunc", stdin = "1").code)

        val runtime = run(".a", stdin = "5")
        assertEquals(5, runtime.code)
        assertEquals("kson: error (at <stdin>): Cannot index number with \"a\"", runtime.err)
        assertEquals("kson: error (at <stdin>) (not a string): {\"c\":1}", run("error({c: 1})", stdin = "1").err)

        val partial = run(".a", stdin = """{"a":1} 5 {"a":3}""")
        assertEquals("1\n3", partial.out, "a runtime error does not stop later inputs")
        assertEquals(5, partial.code)

        val badInput = run(".", stdin = "1 2 {oops")
        assertEquals("1\n2", badInput.out, "values before a syntax error are still processed")
        assertEquals(2, badInput.code)
        assertTrue(badInput.err.startsWith("kson: error (at <stdin>:1:"), badInput.err)
    }

    @Test
    fun jsonFilenameMistakenForFilterGetsFileHint() {
        val result = run("package.json", files = mapOf("package.json" to doc))
        assertEquals(3, result.code)
        assertTrue("compile error" in result.err, result.err)
        assertTrue("kson fmt <file>" in result.err, result.err)
        assertTrue("kson . <file>" in result.err, result.err)
        assertTrue("hint" !in run("nosuchfunc", stdin = doc).err)
    }

    @Test
    fun debugGoesToStderr() {
        val r = run("debug | . + 1", stdin = "1")
        assertEquals("2", r.out)
        assertEquals("[\"DEBUG:\",1]", r.err)
    }

    // --- subcommands -------------------------------------------------------------------

    @Test
    fun fmtAndMin() {
        assertEquals("{\n  \"a\": [\n    1\n  ]\n}", run("fmt", stdin = """{"a":[1]}""").out)
        assertEquals("""{"a":[1]}""", run("min", stdin = "{ \"a\" : [ 1 ] }").out)
        assertEquals("{\n    \"a\": 1\n}", run("fmt", "-i", "4", stdin = """{"a":1}""").out)
        assertEquals("{\n\t\"a\": 1\n}", run("fmt", "--tab", stdin = """{"a":1}""").out)
        assertEquals("""{"a":1,"b":2}""", run("fmt", "-c", "-S", stdin = """{"b":2,"a":1}""").out)
        assertEquals("\"\\u00e9\"", run("min", "--ascii", stdin = "\"é\"").out)
        assertEquals("[1]", run("min", "in.json", files = mapOf("in.json" to "[ 1 ]")).out)
        assertEquals("[1]", run("min", "-", stdin = "[ 1 ]").out)
        assertTrue(run("fmt", stdin = "[1]", tty = true).out.startsWith(esc), "fmt is colored on a terminal")
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
    fun invalidInputOnSubcommandsExitsWithOne() {
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
        assertEquals("""["x"]""", run("build", "--array", "-c", "x").out)
        assertEquals("""["-x"]""", run("build", "-a", "-c", "--", "-x").out)
        assertEquals("{}", run("build").out)
        assertEquals(2, run("build", "novalue").code)
        assertEquals(2, run("build", "n:=nope").code)
        assertEquals(2, run("build", "a..b=1").code)
    }

    @Test
    fun usageErrors() {
        assertEquals(2, run("fmt", "--bogus", stdin = "1").code)
        assertEquals(2, run("--bogus", stdin = "1").code)
        assertEquals(2, run("fmt", "-i", "x", stdin = "1").code)
        assertEquals(2, run("fmt", "a", "b").code)
        assertEquals(2, run("fmt", "missing.json").code)
        assertEquals(0, run("help").code)
        assertTrue(run("--help").out.startsWith("kson "))
        assertEquals("kson $VERSION", run("version").out)
    }
}
