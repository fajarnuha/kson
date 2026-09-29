package com.fajarnuha.kson.query

import com.fajarnuha.kson.JsonArray
import com.fajarnuha.kson.JsonBool
import com.fajarnuha.kson.JsonNull
import com.fajarnuha.kson.JsonNumber
import com.fajarnuha.kson.JsonObject
import com.fajarnuha.kson.JsonParseException
import com.fajarnuha.kson.JsonQueryRuntimeException
import com.fajarnuha.kson.JsonString
import com.fajarnuha.kson.JsonValue
import com.fajarnuha.kson.Json
import com.fajarnuha.kson.jsonType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.exp
import kotlin.math.pow

/** Location of a value inside the root input, kept as a persistent linked list. */
internal class Path private constructor(private val parent: Path?, private val key: JsonValue?) {
    fun plus(k: JsonValue) = Path(this, k)

    fun plusAll(keys: List<JsonValue>): Path = keys.fold(this) { p, k -> p.plus(k) }

    fun toList(): List<JsonValue> {
        val out = ArrayList<JsonValue>()
        var p: Path? = this
        while (p?.key != null) {
            out += p.key!!
            p = p.parent
        }
        out.reverse()
        return out
    }

    companion object {
        val Root = Path(null, null)
    }
}

/** A value flowing through the program; [path] is non-null only while evaluating a path expression. */
internal class Item(val value: JsonValue, val path: Path?)

internal class Closure(val params: List<String>, val body: Node) {
    lateinit var env: Env
}

internal class Env private constructor(
    private val parent: Env?,
    private val varName: String?,
    private val varValue: JsonValue?,
    private val funcKey: String?,
    private val func: Closure?,
) {
    fun bindVar(name: String, value: JsonValue) = Env(this, name, value, null, null)
    fun bindFunc(key: String, closure: Closure) = Env(this, null, null, key, closure)

    fun lookupVar(name: String): JsonValue? {
        var e: Env? = this
        while (e != null) {
            if (e.varName == name) return e.varValue
            e = e.parent
        }
        return null
    }

    fun lookupFunc(key: String): Closure? {
        var e: Env? = this
        while (e != null) {
            if (e.funcKey == key) return e.func
            e = e.parent
        }
        return null
    }

    companion object {
        val Empty = Env(null, null, null, null, null)
    }
}

/** Thrown out of an output callback so that an enclosing `try` does not catch errors raised downstream of it. */
private class EscapedError(val token: Any, val error: JsonQueryRuntimeException) : RuntimeException()

/** Stops a generator early (used by `limit` and `first`). */
private class BreakSignal(val token: Any) : RuntimeException()

internal class Interpreter(
    private val inputs: Iterator<JsonValue>?,
    private val debug: ((JsonValue) -> Unit)?,
) {
    private val regexCache = HashMap<String, CompiledRegex>()

    private fun valueItem(input: Item, v: JsonValue): Item {
        if (input.path != null) fail("Invalid path expression with result ${v.toJson().let { if (it.length > 30) it.take(27) + "..." else it }}")
        return Item(v, null)
    }

    private fun vm(input: Item) = if (input.path == null) input else Item(input.value, null)

    /** Evaluates [node] in value mode and returns every output. */
    fun values(node: Node, input: JsonValue, env: Env): List<JsonValue> {
        val out = ArrayList<JsonValue>()
        eval(node, Item(input, null), env) { out += it.value }
        return out
    }

    fun paths(node: Node, input: JsonValue, env: Env): List<List<JsonValue>> {
        val out = ArrayList<List<JsonValue>>()
        eval(node, Item(input, Path.Root), env) { out += it.path!!.toList() }
        return out
    }

    fun eval(node: Node, input: Item, env: Env, out: (Item) -> Unit) {
        when (node) {
            Node.Identity -> out(input)
            is Node.Literal -> out(valueItem(input, node.value))
            is Node.Var -> {
                val v = env.lookupVar(node.name) ?: fail("\$${node.name} is not defined")
                out(valueItem(input, v))
            }
            is Node.Index -> eval(node.target, input, env) { t ->
                eval(node.key, vm(input), env) { k ->
                    val value = guarded(node.optional) { indexValue(t.value, k.value) }
                    if (value != null) out(Item(value, t.path?.plus(k.value)))
                }
            }
            is Node.Slice -> eval(node.target, input, env) { t ->
                evalOptional(node.from, input, env) { from ->
                    evalOptional(node.to, input, env) { to ->
                        val value = guarded(node.optional) { sliceValue(t.value, from, to) }
                        if (value != null) out(Item(value, t.path?.plus(sliceKey(from, to))))
                    }
                }
            }
            is Node.Iterate -> eval(node.target, input, env) { t ->
                val v = t.value
                if (v is JsonArray || v is JsonObject || !node.optional) iterate(t, out)
            }
            is Node.Try -> evalTry(node, input, env, out)
            is Node.Pipe -> eval(node.left, input, env) { l -> eval(node.right, l, env, out) }
            is Node.Comma -> {
                eval(node.left, input, env, out)
                eval(node.right, input, env, out)
            }
            is Node.Neg -> eval(node.operand, vm(input), env) { v -> out(valueItem(input, negate(v.value))) }
            is Node.Binary -> eval(node.right, vm(input), env) { r ->
                eval(node.left, vm(input), env) { l -> out(valueItem(input, binop(node.op, l.value, r.value))) }
            }
            is Node.And -> eval(node.left, vm(input), env) { l ->
                if (!truthy(l.value)) {
                    out(valueItem(input, JsonBool.False))
                } else {
                    eval(node.right, vm(input), env) { r -> out(valueItem(input, JsonBool.of(truthy(r.value)))) }
                }
            }
            is Node.Or -> eval(node.left, vm(input), env) { l ->
                if (truthy(l.value)) {
                    out(valueItem(input, JsonBool.True))
                } else {
                    eval(node.right, vm(input), env) { r -> out(valueItem(input, JsonBool.of(truthy(r.value)))) }
                }
            }
            is Node.Alt -> {
                val results = ArrayList<Item>()
                eval(node.left, input, env) { if (truthy(it.value)) results += it }
                if (results.isNotEmpty()) results.forEach(out) else eval(node.right, input, env, out)
            }
            is Node.Assign -> evalAssign(node, input, env, out)
            is Node.ArrayCons -> {
                val items = ArrayList<JsonValue>()
                if (node.body != null) eval(node.body, vm(input), env) { items += it.value }
                out(valueItem(input, JsonArray(items)))
            }
            is Node.ObjectCons -> buildObject(node.entries, 0, emptyList(), input, env, out)
            is Node.Str -> buildString(node, 0, "", input, env, out)
            is Node.Format -> out(valueItem(input, JsonString(applyFormat(node.name, input.value))))
            is Node.If -> eval(node.cond, vm(input), env) { c ->
                when {
                    truthy(c.value) -> eval(node.then, input, env, out)
                    node.otherwise != null -> eval(node.otherwise, input, env, out)
                    else -> out(input)
                }
            }
            is Node.Reduce -> eval(node.init, vm(input), env) { initItem ->
                var acc: JsonValue = initItem.value
                eval(node.source, vm(input), env) { s ->
                    bindPattern(node.pattern, s.value, env) { e2 ->
                        var last: JsonValue = JsonNull
                        eval(node.update, Item(acc, null), e2) { u -> last = u.value }
                        acc = last
                    }
                }
                out(valueItem(input, acc))
            }
            is Node.Foreach -> eval(node.init, vm(input), env) { initItem ->
                var state: JsonValue = initItem.value
                eval(node.source, vm(input), env) { s ->
                    bindPattern(node.pattern, s.value, env) { e2 ->
                        eval(node.update, Item(state, null), e2) { u ->
                            state = u.value
                            if (node.extract == null) {
                                out(valueItem(input, state))
                            } else {
                                eval(node.extract, Item(state, null), e2) { x -> out(valueItem(input, x.value)) }
                            }
                        }
                    }
                }
            }
            is Node.FuncDef -> eval(node.rest, input, define(node, env), out)
            is Node.Call -> call(node, input, env, out)
            is Node.Bind -> eval(node.source, vm(input), env) { s ->
                bindPattern(node.pattern, s.value, env) { e2 -> eval(node.body, input, e2, out) }
            }
        }
    }

    fun define(def: Node.FuncDef, env: Env): Env {
        val closure = Closure(def.params, def.body)
        val newEnv = env.bindFunc("${def.name}/${def.params.size}", closure)
        closure.env = newEnv
        return newEnv
    }

    /** Runs [step]; when [optional], a runtime error yields `null` (skip) instead of propagating. */
    private inline fun guarded(optional: Boolean, step: () -> JsonValue): JsonValue? {
        if (!optional) return step()
        return try {
            step()
        } catch (_: JsonQueryRuntimeException) {
            null
        }
    }

    private inline fun evalOptional(node: Node?, input: Item, env: Env, crossinline k: (JsonValue) -> Unit) {
        if (node == null) k(JsonNull) else eval(node, vm(input), env) { k(it.value) }
    }

    private fun iterate(t: Item, out: (Item) -> Unit) {
        when (val v = t.value) {
            is JsonArray -> v.items.forEachIndexed { i, x -> out(Item(x, t.path?.plus(num(i.toLong())))) }
            is JsonObject -> for ((k, x) in v.fields) out(Item(x, t.path?.plus(JsonString(k))))
            JsonNull -> fail("Cannot iterate over null")
            else -> fail("Cannot iterate over ${describe(v)}")
        }
    }

    private fun evalTry(node: Node.Try, input: Item, env: Env, out: (Item) -> Unit) {
        val token = Any()
        try {
            eval(node.body, input, env) { item ->
                try {
                    out(item)
                } catch (e: JsonQueryRuntimeException) {
                    throw EscapedError(token, e)
                }
            }
        } catch (e: JsonQueryRuntimeException) {
            if (node.handler != null) {
                eval(node.handler, Item(e.value, null), env) { h -> out(valueItem(input, h.value)) }
            }
        } catch (e: EscapedError) {
            if (e.token === token) throw e.error
            throw e
        }
    }

    private fun buildObject(
        entries: List<ObjEntry>,
        i: Int,
        acc: List<Pair<String, JsonValue>>,
        input: Item,
        env: Env,
        out: (Item) -> Unit,
    ) {
        if (i == entries.size) {
            val map = LinkedHashMap<String, JsonValue>()
            for ((k, v) in acc) map[k] = v
            out(valueItem(input, JsonObject(map)))
            return
        }
        val entry = entries[i]
        eval(entry.key, vm(input), env) { k ->
            val key = k.value as? JsonString ?: fail("Cannot use ${describe(k.value)} as object key")
            eval(entry.value, vm(input), env) { v -> buildObject(entries, i + 1, acc + (key.value to v.value), input, env, out) }
        }
    }

    private fun buildString(node: Node.Str, i: Int, acc: String, input: Item, env: Env, out: (Item) -> Unit) {
        if (i == node.parts.size) {
            out(valueItem(input, JsonString(acc)))
            return
        }
        when (val part = node.parts[i]) {
            is StrPart.Lit -> buildString(node, i + 1, acc + part.text, input, env, out)
            is StrPart.Interp -> eval(part.node, vm(input), env) { v ->
                buildString(node, i + 1, acc + applyFormat(node.format ?: "text", v.value), input, env, out)
            }
        }
    }

    private fun bindPattern(pattern: Pattern, value: JsonValue, env: Env, k: (Env) -> Unit) {
        when (pattern) {
            is Pattern.Var -> k(env.bindVar(pattern.name, value))
            is Pattern.Arr -> {
                if (value !is JsonArray && value !== JsonNull) fail("Cannot index ${value.jsonType} with number")
                fun go(i: Int, e: Env) {
                    if (i == pattern.elements.size) k(e) else bindPattern(pattern.elements[i], indexValue(value, num(i.toLong())), e) { go(i + 1, it) }
                }
                go(0, env)
            }
            is Pattern.Obj -> {
                fun go(i: Int, e: Env) {
                    if (i == pattern.entries.size) {
                        k(e)
                        return
                    }
                    val entry = pattern.entries[i]
                    eval(entry.key, Item(value, null), e) { keyItem ->
                        val key = keyItem.value as? JsonString ?: fail("Cannot index ${value.jsonType} with ${keyItem.value.jsonType}")
                        val child = indexValue(value, key)
                        val e2 = if (entry.bindVar != null) e.bindVar(entry.bindVar, child) else e
                        if (entry.pattern != null) bindPattern(entry.pattern, child, e2) { go(i + 1, it) } else go(i + 1, e2)
                    }
                }
                go(0, env)
            }
        }
    }

    private fun evalAssign(node: Node.Assign, input: Item, env: Env, out: (Item) -> Unit) {
        val root = input.value
        when (node.op) {
            "|=" -> {
                var result = root
                val deletions = ArrayList<List<JsonValue>>()
                for (p in paths(node.lhs, root, env)) {
                    var replacement: JsonValue? = null
                    val token = Any()
                    try {
                        eval(node.rhs, Item(getPath(result, p), null), env) {
                            replacement = it.value
                            throw BreakSignal(token)
                        }
                    } catch (b: BreakSignal) {
                        if (b.token !== token) throw b
                    }
                    val r = replacement
                    if (r != null) result = setPath(result, p, 0, r) else deletions += p
                }
                if (deletions.isNotEmpty()) result = deletePaths(result, deletions)
                out(valueItem(input, result))
            }
            else -> eval(node.rhs, Item(root, null), env) { rhsItem ->
                val v = rhsItem.value
                var result = root
                for (p in paths(node.lhs, root, env)) {
                    val newValue = when (node.op) {
                        "=" -> v
                        "//=" -> getPath(result, p).let { if (truthy(it)) it else v }
                        else -> binop(node.op.dropLast(1), getPath(result, p), v)
                    }
                    result = setPath(result, p, 0, newValue)
                }
                out(valueItem(input, result))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Function calls
    // -----------------------------------------------------------------------

    private fun call(node: Node.Call, input: Item, env: Env, out: (Item) -> Unit) {
        if (node.native) {
            builtin(node.name, node.args, input, env, out)
            return
        }
        val closure = env.lookupFunc("${node.name}/${node.args.size}")
        if (closure == null) {
            builtin(node.name, node.args, input, env, out)
            return
        }
        if (closure.params.isEmpty()) {
            eval(closure.body, input, closure.env, out)
            return
        }
        bindParams(closure, node.args, 0, closure.env, input, env, out)
    }

    private fun bindParams(c: Closure, args: List<Node>, i: Int, fnEnv: Env, input: Item, callerEnv: Env, out: (Item) -> Unit) {
        if (i == c.params.size) {
            eval(c.body, input, fnEnv, out)
            return
        }
        val param = c.params[i]
        val argClosure = Closure(emptyList(), args[i]).also { it.env = callerEnv }
        if (param.startsWith("$")) {
            val name = param.substring(1)
            eval(args[i], vm(input), callerEnv) { v ->
                val e = fnEnv.bindFunc("$name/0", argClosure).bindVar(name, v.value)
                bindParams(c, args, i + 1, e, input, callerEnv, out)
            }
        } else {
            bindParams(c, args, i + 1, fnEnv.bindFunc("$param/0", argClosure), input, callerEnv, out)
        }
    }

    /** Evaluates value arguments, taking the cartesian product with the first argument varying slowest. */
    private fun withArgs(args: List<Node>, input: Item, env: Env, k: (List<JsonValue>) -> Unit) {
        fun go(i: Int, acc: List<JsonValue>) {
            if (i == args.size) k(acc) else eval(args[i], vm(input), env) { go(i + 1, acc + it.value) }
        }
        go(0, emptyList())
    }

    private fun limit(n: Long, f: Node, input: Item, env: Env, out: (Item) -> Unit) {
        if (n < 0) {
            eval(f, input, env, out)
            return
        }
        if (n == 0L) return
        var count = 0L
        val token = Any()
        try {
            eval(f, input, env) { item ->
                out(item)
                count++
                if (count >= n) throw BreakSignal(token)
            }
        } catch (b: BreakSignal) {
            if (b.token !== token) throw b
        }
    }

    private fun number(v: JsonValue, fn: String): Double =
        (v as? JsonNumber)?.toDouble() ?: fail("${describe(v)} number required")

    private fun string(v: JsonValue, what: String): String =
        (v as? JsonString)?.value ?: fail("${describe(v)} $what")

    private fun sortKeys(f: Node, input: Item, env: Env): List<Pair<JsonValue, JsonValue>> {
        val arr = input.value as? JsonArray ?: fail("Cannot index ${input.value.jsonType} with number")
        return arr.items.map { x -> JsonArray(values(f, x, env)) to x }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun builtin(name: String, args: List<Node>, input: Item, env: Env, out: (Item) -> Unit) {
        val v = input.value
        fun emit(x: JsonValue) = out(valueItem(input, x))
        fun math(f: (Double) -> Double) = emit(num(f(number(v, name))))

        when ("$name/${args.size}") {
            // --- control -------------------------------------------------------
            "empty/0" -> {}
            "error/0" -> throw JsonQueryRuntimeException(v)
            "error/1" -> withArgs(args, input, env) { throw JsonQueryRuntimeException(it[0]) }
            "not/0" -> emit(JsonBool.of(!truthy(v)))
            "path/1" -> eval(args[0], Item(v, Path.Root), env) { emit(JsonArray(it.path!!.toList())) }
            "getpath/1" -> withArgs(args, input, env) { (p) ->
                val keys = (p as? JsonArray)?.items ?: fail("Path must be specified as an array")
                out(Item(getPath(v, keys), input.path?.plusAll(keys)))
            }
            "setpath/2" -> withArgs(args, input, env) { (p, x) ->
                val keys = (p as? JsonArray)?.items ?: fail("Path must be specified as an array")
                emit(setPath(v, keys, 0, x))
            }
            "delpaths/1" -> withArgs(args, input, env) { (ps) ->
                val list = (ps as? JsonArray)?.items ?: fail("Paths must be specified as an array")
                emit(deletePaths(v, list.map { (it as? JsonArray)?.items ?: fail("Path must be specified as an array") }))
            }
            "recurse/0" -> {
                // Native `..`: depth-first, pre-order, path-aware (equivalent to jq's `recurse(.[]?)`).
                fun go(item: Item) {
                    out(item)
                    when (val x = item.value) {
                        is JsonArray -> x.items.forEachIndexed { i, c -> go(Item(c, item.path?.plus(num(i.toLong())))) }
                        is JsonObject -> for ((k, c) in x.fields) go(Item(c, item.path?.plus(JsonString(k))))
                        else -> {}
                    }
                }
                go(input)
            }
            "limit/2" -> withArgs(args.subList(0, 1), input, env) { (n) -> limit(number(n, "limit").toLong(), args[1], input, env, out) }
            "first/1" -> limit(1, args[0], input, env, out)
            "range/2" -> withArgs(args, input, env) { (from, upto) -> range(from, upto, num(1L), input, out) }
            "range/3" -> withArgs(args, input, env) { (from, upto, by) -> range(from, upto, by, input, out) }
            "input/0" -> {
                val it = inputs ?: fail("No more inputs")
                if (!it.hasNext()) fail("No more inputs")
                emit(it.next())
            }
            "inputs/0" -> inputs?.let { while (it.hasNext()) emit(it.next()) }
            "debug/0" -> {
                debug?.invoke(JsonArray(listOf(JsonString("DEBUG:"), v)))
                out(input)
            }
            "stderr/0" -> {
                debug?.invoke(v)
                out(input)
            }
            "input_filename/0" -> emit(JsonNull)
            "env/0" -> emit(env.lookupVar("ENV") ?: JsonObject.Empty)
            "builtins/0" -> emit(JsonArray(BUILTIN_NAMES.map { JsonString(it) }))

            // --- types & conversion -----------------------------------------------
            "type/0" -> emit(JsonString(v.jsonType))
            "length/0" -> emit(lengthOf(v))
            "utf8bytelength/0" -> emit(num(string(v, "only strings have UTF-8 byte length").encodeToByteArray().size.toLong()))
            "tostring/0" -> emit(JsonString(toStringValue(v)))
            "tonumber/0" -> emit(
                when (v) {
                    is JsonNumber -> v
                    is JsonString -> parseNumberText(v.value) ?: fail("Cannot parse '${v.value}' as JSON")
                    else -> fail("${describe(v)} cannot be parsed as a number")
                },
            )
            "tojson/0" -> emit(JsonString(v.toJson()))
            "fromjson/0" -> {
                val s = string(v, "cannot be parsed as JSON")
                emit(
                    try {
                        Json.parse(s)
                    } catch (e: JsonParseException) {
                        fail("${e.description} (while parsing '$s')")
                    },
                )
            }
            "keys/0", "keys_unsorted/0" -> emit(
                when (v) {
                    is JsonObject -> JsonArray(
                        (if (name == "keys") v.keys.sortedWith(::compareStrings) else v.keys.toList()).map { JsonString(it) },
                    )
                    is JsonArray -> JsonArray(v.indices.map { num(it.toLong()) })
                    else -> fail("${describe(v)} has no keys")
                },
            )
            "has/1" -> withArgs(args, input, env) { (k) ->
                emit(
                    JsonBool.of(
                        when {
                            v is JsonObject && k is JsonString -> k.value in v.fields
                            v is JsonArray && k is JsonNumber -> k.toDouble() >= 0 && k.toDouble() < v.size
                            v === JsonNull -> false
                            else -> fail("Cannot check whether ${v.jsonType} has a ${k.jsonType} key")
                        },
                    ),
                )
            }
            "contains/1" -> withArgs(args, input, env) { (b) -> emit(JsonBool.of(containsJson(v, b))) }
            "indices/1" -> withArgs(args, input, env) { (i) -> emit(indicesOf(v, i)) }
            "infinite/0" -> emit(num(Double.MAX_VALUE))
            "isinfinite/0" -> emit(JsonBool.of(abs(number(v, name)) == Double.MAX_VALUE))
            "isvalid/1" -> {
                val ok = try {
                    eval(args[0], vm(input), env) {}
                    true
                } catch (_: JsonQueryRuntimeException) {
                    false
                }
                emit(JsonBool.of(ok))
            }

            // --- math ---------------------------------------------------------------
            "floor/0" -> math(::floor)
            "ceil/0" -> math(::ceil)
            "round/0" -> math { if (it < 0) -floor(-it + 0.5) else floor(it + 0.5) }
            "sqrt/0" -> math(::sqrt)
            "fabs/0", "abs/0" -> math(::abs)
            "log/0" -> math(::ln)
            "log10/0" -> math(::log10)
            "log2/0" -> math(::log2)
            "exp/0" -> math(::exp)
            "exp10/0" -> math { 10.0.pow(it) }
            "trunc/0" -> math { if (it < 0) ceil(it) else floor(it) }
            "pow/2" -> withArgs(args, input, env) { (a, b) -> emit(num(number(a, name).pow(number(b, name)))) }

            // --- arrays ---------------------------------------------------------------
            "sort/0" -> emit(JsonArray((v as? JsonArray ?: fail("${describe(v)} cannot be sorted, as it is not an array")).items.sortedWith(JsonComparator)))
            "sort_by/1" -> emit(JsonArray(sortKeys(args[0], input, env).sortedWith { a, b -> compareJson(a.first, b.first) }.map { it.second }))
            "group_by/1" -> {
                val sorted = sortKeys(args[0], input, env).sortedWith { a, b -> compareJson(a.first, b.first) }
                val groups = ArrayList<JsonValue>()
                var current = ArrayList<JsonValue>()
                var lastKey: JsonValue? = null
                for ((k, x) in sorted) {
                    if (lastKey != null && compareJson(lastKey, k) != 0) {
                        groups.add(JsonArray(current))
                        current = ArrayList()
                    }
                    current += x
                    lastKey = k
                }
                if (current.isNotEmpty()) groups.add(JsonArray(current))
                emit(JsonArray(groups))
            }
            "unique/0", "unique_by/1" -> {
                val keyed = if (args.isEmpty()) {
                    (v as? JsonArray ?: fail("${describe(v)} cannot be sorted, as it is not an array")).items.map { it to it }
                } else {
                    sortKeys(args[0], input, env)
                }
                val sorted = keyed.sortedWith { a, b -> compareJson(a.first, b.first) }
                val result = ArrayList<JsonValue>()
                var lastKey: JsonValue? = null
                for ((k, x) in sorted) {
                    if (lastKey == null || compareJson(lastKey, k) != 0) result += x
                    lastKey = k
                }
                emit(JsonArray(result))
            }
            "min/0", "max/0", "min_by/1", "max_by/1" -> {
                val keyed = if (args.isEmpty()) {
                    (v as? JsonArray ?: fail("Cannot index ${v.jsonType} with number")).items.map { it to it }
                } else {
                    sortKeys(args[0], input, env)
                }
                if (keyed.isEmpty()) {
                    emit(JsonNull)
                } else {
                    val isMax = name.startsWith("max")
                    var best = keyed[0]
                    for (e in keyed.drop(1)) {
                        val c = compareJson(e.first, best.first)
                        if ((isMax && c >= 0) || (!isMax && c < 0)) best = e
                    }
                    emit(best.second)
                }
            }
            "reverse/0" -> emit(
                when (v) {
                    is JsonArray -> JsonArray(v.items.reversed())
                    is JsonString -> JsonString(fromCodePoints(codePoints(v.value).reversed()))
                    JsonNull -> JsonArray.Empty
                    else -> fail("Cannot reverse ${describe(v)}")
                },
            )
            "flatten/0" -> emit(flatten(v, 1e9))
            "flatten/1" -> withArgs(args, input, env) { (d) -> emit(flatten(v, number(d, name))) }
            "join/1" -> withArgs(args, input, env) { (sep) ->
                val arr = v as? JsonArray ?: fail("Cannot iterate over ${describe(v)}")
                val s = string(sep, "cannot be used as a separator")
                emit(
                    JsonString(
                        arr.items.joinToString(s) { x ->
                            when (x) {
                                JsonNull -> ""
                                is JsonString -> x.value
                                is JsonNumber, is JsonBool -> x.toJson()
                                else -> fail("Cannot join with ${x.jsonType}")
                            }
                        },
                    ),
                )
            }
            "implode/0" -> {
                val arr = v as? JsonArray ?: fail("Cannot implode ${describe(v)}")
                emit(JsonString(fromCodePoints(arr.items.map { (it as? JsonNumber)?.toInt() ?: fail("Unicode codepoint must be numeric") })))
            }
            "explode/0" -> emit(JsonArray(codePoints(string(v, "cannot be exploded")).map { num(it.toLong()) }))
            "tostream/0" -> {
                fun go(path: List<JsonValue>, x: JsonValue) {
                    val children: List<Pair<JsonValue, JsonValue>>? = when (x) {
                        is JsonArray -> x.items.mapIndexed { i, c -> num(i.toLong()) to c }
                        is JsonObject -> x.fields.map { (k, c) -> JsonString(k) to c }
                        else -> null
                    }
                    if (children.isNullOrEmpty()) {
                        emit(JsonArray(listOf(JsonArray(path), x)))
                    } else {
                        for ((k, c) in children) go(path + k, c)
                        emit(JsonArray(listOf(JsonArray(path + children.last().first))))
                    }
                }
                go(emptyList(), v)
            }

            // --- strings --------------------------------------------------------------
            "startswith/1" -> withArgs(args, input, env) { (s) ->
                if (v !is JsonString || s !is JsonString) fail("startswith() requires string inputs")
                emit(JsonBool.of(v.value.startsWith(s.value)))
            }
            "endswith/1" -> withArgs(args, input, env) { (s) ->
                if (v !is JsonString || s !is JsonString) fail("endswith() requires string inputs")
                emit(JsonBool.of(v.value.endsWith(s.value)))
            }
            "ltrimstr/1" -> withArgs(args, input, env) { (s) ->
                emit(if (v is JsonString && s is JsonString && v.value.startsWith(s.value)) JsonString(v.value.substring(s.value.length)) else v)
            }
            "rtrimstr/1" -> withArgs(args, input, env) { (s) ->
                emit(if (v is JsonString && s is JsonString && v.value.endsWith(s.value) && s.value.isNotEmpty()) JsonString(v.value.dropLast(s.value.length)) else v)
            }
            "trim/0", "ltrim/0", "rtrim/0" -> {
                val s = string(v, "trim input must be a string")
                emit(JsonString(when (name) { "trim" -> s.trim(); "ltrim" -> s.trimStart(); else -> s.trimEnd() }))
            }
            "ascii_downcase/0" -> emit(JsonString(string(v, "cannot be lowercased").map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")))
            "ascii_upcase/0" -> emit(JsonString(string(v, "cannot be uppercased").map { if (it in 'a'..'z') it - 32 else it }.joinToString("")))
            "split/1" -> withArgs(args, input, env) { (sep) ->
                if (v !is JsonString || sep !is JsonString) fail("split input and separator must be strings")
                emit(splitString(v.value, sep.value))
            }
            "split/2" -> withArgs(args, input, env) { (re, flags) ->
                val s = string(v, "cannot be matched, as it is not a string")
                val r = compileRegex(re, flags, regexCache)
                val parts = ArrayList<JsonValue>()
                var last = 0
                for (m in matches(r, s, forceGlobal = true)) {
                    parts += JsonString(s.substring(last, m.range.first))
                    last = m.range.last + 1
                }
                parts += JsonString(s.substring(last))
                emit(JsonArray(parts))
            }
            "test/1", "test/2" -> withArgs(args, input, env) { a ->
                val s = string(v, "cannot be matched, as it is not a string")
                val r = compileRegex(a[0], a.getOrElse(1) { JsonNull }, regexCache)
                emit(JsonBool.of(r.regex.containsMatchIn(s)))
            }
            "match/1", "match/2" -> withArgs(args, input, env) { a ->
                val s = string(v, "cannot be matched, as it is not a string")
                val r = compileRegex(a[0], a.getOrElse(1) { JsonNull }, regexCache)
                for (m in matches(r, s)) emit(matchObject(r, m))
            }
            "capture/1", "capture/2" -> withArgs(args, input, env) { a ->
                val s = string(v, "cannot be matched, as it is not a string")
                val r = compileRegex(a[0], a.getOrElse(1) { JsonNull }, regexCache)
                for (m in matches(r, s)) emit(captureObject(r, m))
            }
            "scan/1", "scan/2" -> withArgs(args, input, env) { a ->
                val s = string(v, "cannot be matched, as it is not a string")
                val r = compileRegex(a[0], a.getOrElse(1) { JsonNull }, regexCache)
                for (m in matches(r, s, forceGlobal = true)) {
                    if (m.groups.size <= 1) {
                        emit(JsonString(m.value))
                    } else {
                        emit(JsonArray((1 until m.groups.size).map { g -> m.groups[g]?.value?.let { JsonString(it) } ?: JsonNull }))
                    }
                }
            }
            "sub/2", "sub/3", "gsub/2", "gsub/3" -> {
                val flagsNode = args.getOrNull(2)
                withArgs(listOf(args[0]) + listOfNotNull(flagsNode), input, env) { a ->
                    val s = string(v, "cannot be matched, as it is not a string")
                    var flags = (a.getOrNull(1) as? JsonString)?.value ?: ""
                    if (name == "gsub" && 'g' !in flags) flags += "g"
                    val r = compileRegex(a[0], JsonString(flags), regexCache)
                    val found = matches(r, s)
                    fun go(mi: Int, acc: String, lastEnd: Int) {
                        if (mi == found.size) {
                            emit(JsonString(acc + s.substring(lastEnd)))
                            return
                        }
                        val m = found[mi]
                        eval(args[1], Item(captureObject(r, m), null), env) { rep ->
                            val text = rep.value as? JsonString ?: fail("${describe(JsonString(acc))} and ${describe(rep.value)} cannot be added")
                            go(mi + 1, acc + s.substring(lastEnd, m.range.first) + text.value, m.range.last + 1)
                        }
                    }
                    go(0, "", 0)
                }
            }
            "splits/1", "splits/2" -> withArgs(args, input, env) { a ->
                val s = string(v, "cannot be matched, as it is not a string")
                val r = compileRegex(a[0], a.getOrElse(1) { JsonNull }, regexCache)
                var last = 0
                for (m in matches(r, s, forceGlobal = true)) {
                    emit(JsonString(s.substring(last, m.range.first)))
                    last = m.range.last + 1
                }
                emit(JsonString(s.substring(last)))
            }
            else -> fail("$name/${args.size} is not defined")
        }
    }

    private fun range(from: JsonValue, upto: JsonValue, by: JsonValue, input: Item, out: (Item) -> Unit) {
        if (from !is JsonNumber || upto !is JsonNumber || by !is JsonNumber) fail("Range bounds must be numeric")
        val a = from.toLongOrNull()
        val b = upto.toLongOrNull()
        val s = by.toLongOrNull()
        if (a != null && b != null && s != null) {
            if (s > 0) {
                var i = a
                while (i < b) {
                    out(valueItem(input, num(i)))
                    i += s
                }
            } else if (s < 0) {
                var i = a
                while (i > b) {
                    out(valueItem(input, num(i)))
                    i += s
                }
            }
            return
        }
        val step = by.toDouble()
        var x = from.toDouble()
        val end = upto.toDouble()
        if (step > 0) {
            while (x < end) {
                out(valueItem(input, num(x)))
                x += step
            }
        } else if (step < 0) {
            while (x > end) {
                out(valueItem(input, num(x)))
                x += step
            }
        }
    }

    companion object {
        /** Natively implemented builtins as `name/arity`. */
        val NATIVE: Set<String> = setOf(
            "empty/0", "error/0", "error/1", "recurse/0", "not/0", "path/1", "getpath/1", "setpath/2", "delpaths/1", "limit/2",
            "first/1", "range/2", "range/3", "input/0", "inputs/0", "debug/0", "stderr/0", "input_filename/0", "env/0",
            "builtins/0", "type/0", "length/0", "utf8bytelength/0", "tostring/0", "tonumber/0", "tojson/0", "fromjson/0",
            "keys/0", "keys_unsorted/0", "has/1", "contains/1", "indices/1", "infinite/0", "isinfinite/0", "isvalid/1",
            "floor/0", "ceil/0", "round/0", "sqrt/0", "fabs/0", "abs/0", "log/0", "log10/0", "log2/0", "exp/0", "exp10/0",
            "trunc/0", "pow/2", "sort/0", "sort_by/1", "group_by/1", "unique/0", "unique_by/1", "min/0", "max/0",
            "min_by/1", "max_by/1", "reverse/0", "flatten/0", "flatten/1", "join/1", "implode/0", "explode/0",
            "tostream/0", "startswith/1", "endswith/1", "ltrimstr/1", "rtrimstr/1", "trim/0", "ltrim/0", "rtrim/0",
            "ascii_downcase/0", "ascii_upcase/0", "split/1", "split/2", "test/1", "test/2", "match/1", "match/2",
            "capture/1", "capture/2", "scan/1", "scan/2", "sub/2", "sub/3", "gsub/2", "gsub/3",
            "splits/1", "splits/2",
        )

        val BUILTIN_NAMES: List<String> get() = (NATIVE + Prelude.keys).sorted()
    }
}
