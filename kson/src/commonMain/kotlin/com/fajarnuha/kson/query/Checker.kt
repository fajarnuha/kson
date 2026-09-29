package com.fajarnuha.kson.query

import com.fajarnuha.kson.JsonQuerySyntaxException

/** Compile-time validation: every called function and `@format` must exist. */
internal object Checker {
    private class Scope(val parent: Scope?, val key: String) {
        fun has(k: String): Boolean {
            var s: Scope? = this
            while (s != null) {
                if (s.key == k) return true
                s = s.parent
            }
            return false
        }
    }

    private var preludeKeys: Set<String> = emptySet()

    /** Validates [program] and marks calls that resolve to native builtins. */
    fun check(program: Node, prelude: Set<String> = Prelude.keys) {
        preludeKeys = prelude
        visit(program, null)
    }

    private fun known(key: String, scope: Scope?) =
        scope?.has(key) == true || key in Interpreter.NATIVE || key in preludeKeys

    private fun visit(node: Node?, scope: Scope?) {
        when (node) {
            null, Node.Identity, is Node.Literal, is Node.Var -> {}
            is Node.Str -> {
                node.format?.let(::checkFormat)
                node.parts.forEach { if (it is StrPart.Interp) visit(it.node, scope) }
            }
            is Node.Format -> checkFormat(node.name)
            is Node.Index -> {
                visit(node.target, scope)
                visit(node.key, scope)
            }
            is Node.Slice -> {
                visit(node.target, scope)
                visit(node.from, scope)
                visit(node.to, scope)
            }
            is Node.Iterate -> visit(node.target, scope)
            is Node.Try -> {
                visit(node.body, scope)
                visit(node.handler, scope)
            }
            is Node.Pipe -> {
                visit(node.left, scope)
                visit(node.right, scope)
            }
            is Node.Comma -> {
                visit(node.left, scope)
                visit(node.right, scope)
            }
            is Node.Neg -> visit(node.operand, scope)
            is Node.Binary -> {
                visit(node.left, scope)
                visit(node.right, scope)
            }
            is Node.And -> {
                visit(node.left, scope)
                visit(node.right, scope)
            }
            is Node.Or -> {
                visit(node.left, scope)
                visit(node.right, scope)
            }
            is Node.Alt -> {
                visit(node.left, scope)
                visit(node.right, scope)
            }
            is Node.Assign -> {
                visit(node.lhs, scope)
                visit(node.rhs, scope)
            }
            is Node.ArrayCons -> visit(node.body, scope)
            is Node.ObjectCons -> node.entries.forEach {
                visit(it.key, scope)
                visit(it.value, scope)
            }
            is Node.If -> {
                visit(node.cond, scope)
                visit(node.then, scope)
                visit(node.otherwise, scope)
            }
            is Node.Reduce -> {
                visit(node.source, scope)
                visitPattern(node.pattern, scope)
                visit(node.init, scope)
                visit(node.update, scope)
            }
            is Node.Foreach -> {
                visit(node.source, scope)
                visitPattern(node.pattern, scope)
                visit(node.init, scope)
                visit(node.update, scope)
                visit(node.extract, scope)
            }
            is Node.FuncDef -> {
                val self = Scope(scope, "${node.name}/${node.params.size}")
                var inner: Scope = self
                for (p in node.params) inner = Scope(inner, "${p.removePrefix("$")}/0")
                visit(node.body, inner)
                visit(node.rest, self)
            }
            is Node.Call -> {
                val key = "${node.name}/${node.args.size}"
                if (!known(key, scope)) throw JsonQuerySyntaxException("$key is not defined", node.pos)
                node.native = key in Interpreter.NATIVE && scope?.has(key) != true && key !in preludeKeys
                node.args.forEach { visit(it, scope) }
            }
            is Node.Bind -> {
                visit(node.source, scope)
                visitPattern(node.pattern, scope)
                visit(node.body, scope)
            }
        }
    }

    private fun visitPattern(p: Pattern, scope: Scope?) {
        when (p) {
            is Pattern.Var -> {}
            is Pattern.Arr -> p.elements.forEach { visitPattern(it, scope) }
            is Pattern.Obj -> p.entries.forEach { e ->
                visit(e.key, scope)
                e.pattern?.let { visitPattern(it, scope) }
            }
        }
    }

    private fun checkFormat(name: String) {
        if (name !in FORMATS) throw JsonQuerySyntaxException("@$name is not a valid format", 0)
    }
}
