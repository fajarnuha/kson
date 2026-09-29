package com.fajarnuha.kson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * jq-language tests. Expected outputs were checked against jq 1.7.1 (see scripts/jq-parity.sh,
 * which runs a larger corpus against the real jq binary).
 */
class QueryTest {
    /** Runs [filter] on [input] (JSON text) and returns the outputs as compact JSON joined by " | ". */
    private fun q(filter: String, input: String = "null", vars: Map<String, JsonValue> = emptyMap()): String =
        JsonQuery.compile(filter).all(Json.parse(input), vars).joinToString(" | ") { it.toJson() }

    @Test
    fun pathsAndIteration() {
        val doc = """{"a":{"b":[10,20,{"c":"x"}]},"k":"a"}"""
        assertEquals(doc, q(".", doc))
        assertEquals("20", q(".a.b[1]", doc))
        assertEquals("{\"c\":\"x\"}", q(".a.b[-1]", doc))
        assertEquals("null", q(".nope.deeper", doc))
        assertEquals("10 | 20 | {\"c\":\"x\"}", q(".a.b[]", doc))
        assertEquals("[20,{\"c\":\"x\"}]", q(".a.b[1:]", doc))
        assertEquals("\"bc\"", q(".[1:3]", "\"abcd\""))
        assertEquals("{\"b\":[10,20,{\"c\":\"x\"}]}", q(".[.k]", doc))
        assertEquals("5", q(""".["a-b"]""", """{"a-b":5}"""))
        assertEquals("5", q("""."a-b"""", """{"a-b":5}"""))
        assertEquals("[1,2,3]", q("[..|numbers]", """{"a":1,"b":{"c":2,"d":[3]}}"""))
    }

    @Test
    fun optionalBindsToOneStep() {
        assertEquals("[2,4]", q("[.[].a?]", """[1,{"a":2},3,{"a":4}]"""))
        assertEquals("[]", q("[(.[] | .a)?]", """[1,{"a":2}]"""))
        assertEquals("[5]", q("[.a[]?, 5]", """{"a":1}"""))
        assertFailsWith<JsonQueryRuntimeException> { q(".a", "5") }
    }

    @Test
    fun operators() {
        assertEquals("5", q("1 + 2 * 3 - 4 / 2"))
        assertEquals("1 | -1 | 1", q("10 % 3, -10 % 3, 10 % -3"))
        assertEquals("11 | 12 | 21 | 22", q("(1,2) + (10,20)"))
        assertEquals("\"xy\" | [1,2] | {\"a\":1,\"b\":2}", q("\"x\" + \"y\", [1] + [2], {a:1} + {b:2}"))
        assertEquals("[1,3]", q("[1,2,3,2] - [2]"))
        assertEquals("{\"a\":{\"b\":1,\"c\":2}}", q("{a:{b:1}} * {a:{c:2}}"))
        assertEquals("[\"a\",\"b\"]", q("\"a,b\" / \",\""))
        assertEquals("0.30000000000000004 | 0.3333333333333333 | 1e-07 | 1e+17", q("0.1 + 0.2, 1 / 3, 1e-7 * 1, 1e17 * 1"))
        assertEquals("9007199254740993", q("9007199254740992 + 1"), "integer arithmetic stays exact")
        assertEquals("-0 | 3", q("-0, -(-3)"))
        assertEquals("true | true | true | true", q("null < false, false < true, 1 < \"a\", [] < {}"))
        assertEquals("true | false", q("true and (true, false)"))
        assertEquals("false", q("false and error(\"never evaluated\")"))
        assertEquals("\"d\" | 0", q(".a // \"d\", .b // \"d\"", """{"a":null,"b":0}"""))
        assertFailsWith<JsonQueryRuntimeException> { q("1 / 0") }
        assertFailsWith<JsonQueryRuntimeException> { q("{} + 1") }
    }

    @Test
    fun construction() {
        assertEquals("""{"a":1,"b":2,"dyn":3}""", q("""{a: .x, "b": 2, (.k): 3}""", """{"x":1,"k":"dyn"}"""))
        assertEquals("""{"a":1,"b":2}""", q("{a, b}", """{"a":1,"b":2,"c":3}"""))
        assertEquals("""{"a":1} | {"a":2}""", q("{a: (1,2)}"))
        assertEquals("""{"x":5}""", q(". as \$x | {\$x}", "5"))
        assertEquals("[0,1,2]", q("[range(3)]"))
        assertEquals("\"hi Ann, 31\"", q("\"hi \\(.n), \\(.a + 1)\"", """{"n":"Ann","a":30}"""))
    }

    @Test
    fun formats() {
        assertEquals("\"aGk=\" | \"hi\"", q("@base64, (@base64 | @base64d)", "\"hi\""))
        assertEquals("\"1,\\\"a,b\\\",\"", q("@csv", """[1,"a,b",null]"""))
        assertEquals("\"a\\tb\"", q("@tsv", """["a","b"]"""))
        assertEquals("\"'a b' 'it'\\\\''s'\"", q("@sh", """["a b","it's"]"""))
        assertEquals("\"a%20b%C3%A9\"", q("@uri", "\"a bé\""))
        assertEquals("\"&lt;&amp;&apos;&quot;&gt;\"", q("@html", "\"<&'\\\">\""))
        assertEquals("\"v=[1]\"", q("@json \"v=\\(.)\"", "[1]"))
    }

    @Test
    fun controlFlow() {
        assertEquals("\"mid\"", q("if . > 2 then \"big\" elif . > 1 then \"mid\" else \"small\" end", "2"))
        assertEquals("false", q("if . then \"yes\" end", "false"))
        assertEquals("\"boom\"", q("try error(\"boom\") catch ."))
        assertEquals("1 | \"caught\"", q("try (1, error(\"x\"), 3) catch \"caught\""))
        assertEquals("[1,3]", q("[.[] | tonumber?]", """["1","x","3"]"""))
        assertEquals("10", q("reduce .[] as \$x (0; . + \$x)", "[1,2,3,4]"))
        assertEquals("[1,3,6]", q("[foreach .[] as \$x (0; . + \$x)]", "[1,2,3]"))
        assertEquals("3628800", q("def fac: if . <= 1 then 1 else . * (. - 1 | fac) end; fac", "10"))
        assertEquals("[2,3]", q("def f(g): [.[] | g]; f(. + 1)", "[1,2]"))
        assertEquals("11", q("def f(\$n): . + \$n; f(10)", "1"))
        assertEquals("[1,2,3]", q(". as {a: \$x, b: [\$y, \$z]} | [\$x, \$y, \$z]", """{"a":1,"b":[2,3]}"""))
        assertEquals("[1,2,4,8,16]", q("[limit(5; repeat(. * 2))]", "1"))
        assertEquals("[1,2,4,8,16]", q("[while(. < 20; . * 2)]", "1"))
    }

    @Test
    fun assignment() {
        assertEquals("""{"a":{"b":{"c":1}}}""", q(".a.b.c = 1"))
        assertEquals("[null,null,1]", q(".[2] = 1"))
        assertEquals("""{"a":2}""", q(".a |= . + 1", """{"a":1}"""))
        assertEquals("[2,4,6]", q(".[] |= . * 2", "[1,2,3]"))
        assertEquals("""{"a":11}""", q(".a += 10", """{"a":1}"""))
        assertEquals("""{"a":"d"}""", q(".a //= \"d\"", """{"a":null}"""))
        assertEquals("[]", q(".[] |= empty", "[1,2,3,4,5]"))
        assertEquals("""{"a":2,"b":[3,{"c":4}]}""", q("(.. | numbers) |= . + 1", """{"a":1,"b":[2,{"c":3}]}"""))
        assertEquals("""[1,"x",4]""", q(".[1:3] = [\"x\"]", "[1,2,3,4]"))
        assertEquals("""{"a":1} | {"a":2}""", q(".a = (1,2)", "{}"))
    }

    @Test
    fun pathFunctions() {
        val doc = """{"a":[1,{"b":2}]}"""
        assertEquals("""[["a"],["a",0],["a",1],["a",1,"b"]]""", q("[paths]", doc))
        assertEquals("""[["a",0],["a",1,"b"]]""", q("[paths(type == \"number\")]", doc))
        assertEquals("""["a",0,"b"]""", q("path(.a[0].b)"))
        assertEquals("""{"b":2}""", q("del(.a, .c)", """{"a":1,"b":2,"c":3}"""))
        assertEquals("[1,2]", q("del(.[] | select(. > 2))", "[1,5,2,7]"))
        assertEquals("""{"a":{"b":1},"c":[null,2]}""", q("pick(.a.b, .c[1])", """{"a":{"b":1,"x":2},"c":[1,2,3]}"""))
        assertEquals("""{"a":[null,9]}""", q("setpath([\"a\",1]; 9)", "{}"))
        assertEquals("5", q("getpath([\"a\",\"b\"])", """{"a":{"b":5}}"""))
        assertEquals("""[[["a"],1],[["a"]]]""", q("[tostream]", """{"a":1}"""))
        assertFailsWith<JsonQueryRuntimeException> { q("path(1)") }
    }

    @Test
    fun builtins() {
        assertEquals("[\"A\",\"a\",\"b\"]", q("keys", """{"b":1,"a":2,"A":3}"""))
        assertEquals("[0,5,5,2,1]", q("[.[] | length]", """[null,-5,"héllo",[1,2],{"a":1}]"""))
        assertEquals("""{"b":2,"c":3}""", q("to_entries | map(select(.value > 1)) | from_entries", """{"a":1,"b":2,"c":3}"""))
        assertEquals("""{"a":2,"b":3}""", q("with_entries(.value += 1)", """{"a":1,"b":2}"""))
        assertEquals("6 | \"ab\" | null", q("([1,2,3], [\"a\",\"b\"], []) | add"))
        assertEquals("[[{\"t\":\"x\"},{\"t\":\"x\"}],[{\"t\":\"y\"}]]", q("group_by(.t)", """[{"t":"x"},{"t":"y"},{"t":"x"}]"""))
        assertEquals("[1,2,3]", q("unique", "[3,1,2,1,3]"))
        assertEquals("""{"a":1} | {"a":3}""", q("min_by(.a), max_by(.a)", """[{"a":2},{"a":1},{"a":3}]"""))
        assertEquals("[null,false,true,1,\"a\",[1],{\"a\":1}]", q("sort", """[{"a":1},[1],"a",1,true,false,null]"""))
        assertEquals("true | true", q("contains(\"bar\"), (\"bar\" | inside(\"foobar\"))", "\"foobar\""))
        assertEquals("[1,3,5]", q("indices(1)", "[0,1,2,1,3,1,4]"))
        assertEquals("\"a, b\"", q("join(\", \")", """["a","b"]"""))
        assertEquals("[1,2,[3]] | [1,2,3]", q("flatten(1), flatten", "[1,[2,[3]]]"))
        assertEquals("-3 | -2 | 1 | 3", q(".[] | round", "[-2.5,-1.5,0.5,2.5]"))
        assertEquals("[2,3]", q("walk(if type == \"number\" then . + 1 else . end)", "[1,2]"))
        assertEquals("[[1,3],[2,null]]", q("transpose", "[[1,2],[3]]"))
        assertEquals("true", q("any(.[]; . == 2)", "[1,2]"))
        assertEquals("true", q("IN(2, 3)", "2"))
        assertEquals("""{"1":{"id":1},"2":{"id":2}}""", q("INDEX(.id)", """[{"id":1},{"id":2}]"""))
        assertEquals("\"hi\" | [1] | 5", q("(\"  hi  \" | trim), (1 | toarray), (-5 | abs)"))
        assertEquals("\"aé😀\"", q("explode | implode", "\"aé😀\""))
    }

    @Test
    fun regex() {
        assertEquals("true | false", q(".[] | test(\"a.c\"; \"i\")", """["ABC","xyz"]"""))
        assertEquals("""{"y":"2026","m":"09"}""", q("capture(\"(?<y>\\\\d+)-(?<m>\\\\d+)\")", "\"2026-09\""))
        assertEquals("[\"c\",\"c\"]", q("[scan(\"c\")]", "\"abcdc\""))
        assertEquals("\"bXnana\" | \"bXnXnX\"", q("sub(\"a\"; \"X\"), gsub(\"a\"; \"X\")", "\"banana\""))
        assertEquals("\"<a><b>1\"", q("gsub(\"(?<x>[a-z])\"; \"<\\(.x)>\")", "\"ab1\""))
        assertEquals("[\"a\",\"b\",\"c\"]", q("[splits(\"\\\\d\")]", "\"a1b2c\""))
        assertEquals("[1,3,5]", q("[match(\"a\"; \"g\").offset]", "\"banana\""))
    }

    @Test
    fun apiVariablesInputsAndDebug() {
        val doc = Json.parse("""{"users":[{"name":"a","age":40},{"name":"b","age":20}]}""")
        assertEquals(listOf(JsonString("a")), doc.query(".users[] | select(.age > \$min) | .name", mapOf("min" to JsonNumber(30))))
        assertEquals(JsonString("a"), doc.queryFirst(".users[].name"))
        assertNull(doc.queryFirst("empty"))
        assertEquals(JsonNumber(0), JsonNumber(0).queryFirst("first(range(1000000000))"), "first() stops a huge generator")

        val out = ArrayList<JsonValue>()
        JsonQuery.compile("[., input, input]").run(JsonNumber(1), inputs = listOf(JsonNumber(2), JsonNumber(3)).iterator()) { out += it }
        assertEquals("[1,2,3]", out.single().toJson())

        val logged = ArrayList<String>()
        JsonQuery.compile("debug | . + 1").run(JsonNumber(1), debug = { logged += it.toJson() }) { out += it }
        assertEquals(listOf("[\"DEBUG:\",1]"), logged)
    }

    @Test
    fun errors() {
        val syntax = assertFailsWith<JsonQuerySyntaxException> { JsonQuery.compile(".a | ") }
        assertTrue(syntax.offset >= 4, syntax.message)
        assertFailsWith<JsonQuerySyntaxException> { JsonQuery.compile("nosuchfunc") }
        assertFailsWith<JsonQuerySyntaxException> { JsonQuery.compile("@nope") }
        assertFailsWith<JsonQuerySyntaxException> { JsonQuery.compile("{") }
        val runtime = assertFailsWith<JsonQueryRuntimeException> { q("error({\"code\": 7})") }
        assertEquals("""{"code":7}""", runtime.value.toJson())
        assertEquals("""Cannot index number with "a"""", assertFailsWith<JsonQueryRuntimeException> { q(".a", "1") }.message)
    }
}
