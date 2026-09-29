package com.fajarnuha.kson.query

import com.fajarnuha.kson.JsonValue

/** Abstract syntax tree of a jq program. */
internal sealed class Node {
    data object Identity : Node()
    class Literal(val value: JsonValue) : Node()
    /** A string literal with `\(...)` interpolations, optionally prefixed by a `@format`. */
    class Str(val parts: List<StrPart>, val format: String?) : Node()
    class Format(val name: String) : Node()
    /** `optional` is set by a trailing `?`: errors from this one indexing step are skipped (jq's `.a?`, `.[]?`). */
    class Index(val target: Node, val key: Node, val optional: Boolean = false) : Node()
    class Slice(val target: Node, val from: Node?, val to: Node?, val optional: Boolean = false) : Node()
    class Iterate(val target: Node, val optional: Boolean = false) : Node()
    class Try(val body: Node, val handler: Node?) : Node()
    class Pipe(val left: Node, val right: Node) : Node()
    class Comma(val left: Node, val right: Node) : Node()
    class Neg(val operand: Node) : Node()
    class Binary(val op: String, val left: Node, val right: Node) : Node()
    class And(val left: Node, val right: Node) : Node()
    class Or(val left: Node, val right: Node) : Node()
    class Alt(val left: Node, val right: Node) : Node()
    class Assign(val op: String, val lhs: Node, val rhs: Node) : Node()
    class ArrayCons(val body: Node?) : Node()
    class ObjectCons(val entries: List<ObjEntry>) : Node()
    class If(val cond: Node, val then: Node, val otherwise: Node?) : Node()
    class Reduce(val source: Node, val pattern: Pattern, val init: Node, val update: Node) : Node()
    class Foreach(val source: Node, val pattern: Pattern, val init: Node, val update: Node, val extract: Node?) : Node()
    class FuncDef(val name: String, val params: List<String>, val body: Node, val rest: Node) : Node()
    class Call(val name: String, val args: List<Node>, val pos: Int) : Node() {
        /** Set by [Checker] when this call lexically resolves to a native builtin, which skips the environment lookup. */
        var native: Boolean = false
    }
    class Var(val name: String) : Node()
    class Bind(val source: Node, val pattern: Pattern, val body: Node) : Node()
}

internal class ObjEntry(val key: Node, val value: Node)

internal sealed class StrPart {
    class Lit(val text: String) : StrPart()
    class Interp(val node: Node) : StrPart()
}

/** Destructuring patterns used by `as`, `reduce` and `foreach`. */
internal sealed class Pattern {
    class Var(val name: String) : Pattern()
    class Arr(val elements: List<Pattern>) : Pattern()
    class Obj(val entries: List<ObjPatternEntry>) : Pattern()
}

/** `$name`, `$name: pattern`, `key: pattern`, `"key": pattern` or `(expr): pattern`. */
internal class ObjPatternEntry(val key: Node, val bindVar: String?, val pattern: Pattern?)
