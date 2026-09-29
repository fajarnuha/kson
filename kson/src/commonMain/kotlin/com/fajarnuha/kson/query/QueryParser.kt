package com.fajarnuha.kson.query

import com.fajarnuha.kson.JsonNumber
import com.fajarnuha.kson.JsonQuerySyntaxException
import com.fajarnuha.kson.JsonString

internal enum class Tk { IDENT, FIELD, VAR, FORMAT, NUMBER, STRING, DOT, DOTDOT, PUNCT, EOF }

internal class Token(val type: Tk, val text: String, val pos: Int, val parts: List<StrPart>? = null) {
    fun isPunct(p: String) = type == Tk.PUNCT && text == p
    fun isKeyword(k: String) = type == Tk.IDENT && text == k
    override fun toString() = if (type == Tk.EOF) "end of input" else "'$text'"
}

private fun isIdentStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'
private fun isIdentPart(c: Char) = isIdentStart(c) || c in '0'..'9'

internal class QueryLexer(private val src: String, private val base: Int) {
    private var i = 0
    private val n = src.length

    fun tokens(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            val t = next()
            out += t
            if (t.type == Tk.EOF) return out
        }
    }

    private fun fail(msg: String, at: Int = i): Nothing = throw JsonQuerySyntaxException(msg, base + at)

    private fun skipSpace() {
        while (i < n) {
            val c = src[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++
            } else if (c == '#') {
                while (i < n && src[i] != '\n') i++
            } else {
                return
            }
        }
    }

    private fun readIdent(): String {
        val s = i
        while (i < n && isIdentPart(src[i])) i++
        return src.substring(s, i)
    }

    private fun next(): Token {
        skipSpace()
        if (i >= n) return Token(Tk.EOF, "", base + i)
        val start = i
        val c = src[i]
        when {
            c == '"' -> return readString()
            c == '.' -> {
                if (i + 1 < n && src[i + 1] == '.') {
                    i += 2
                    return Token(Tk.DOTDOT, "..", base + start)
                }
                if (i + 1 < n && isIdentStart(src[i + 1])) {
                    i++
                    return Token(Tk.FIELD, readIdent(), base + start)
                }
                if (i + 1 < n && src[i + 1] in '0'..'9') return readNumber()
                i++
                return Token(Tk.DOT, ".", base + start)
            }
            c in '0'..'9' -> return readNumber()
            c == '$' -> {
                i++
                if (i >= n || !isIdentStart(src[i])) fail("Expected a variable name after '$'")
                return Token(Tk.VAR, readIdent(), base + start)
            }
            c == '@' -> {
                i++
                if (i >= n || !isIdentStart(src[i])) fail("Expected a format name after '@'")
                return Token(Tk.FORMAT, readIdent(), base + start)
            }
            isIdentStart(c) -> return Token(Tk.IDENT, readIdent(), base + start)
        }
        for (op in OPERATORS) {
            if (src.startsWith(op, i)) {
                i += op.length
                return Token(Tk.PUNCT, op, base + start)
            }
        }
        fail("Unexpected character '$c'")
    }

    private fun readNumber(): Token {
        val start = i
        val intStart = i
        while (i < n && src[i] in '0'..'9') i++
        var intPart = src.substring(intStart, i).trimStart('0')
        if (intPart.isEmpty()) intPart = "0"
        var frac = ""
        if (i < n && src[i] == '.') {
            i++
            val fs = i
            while (i < n && src[i] in '0'..'9') i++
            frac = src.substring(fs, i)
        }
        var exp = ""
        if (i < n && (src[i] == 'e' || src[i] == 'E')) {
            val es = i
            i++
            if (i < n && (src[i] == '+' || src[i] == '-')) i++
            if (i >= n || src[i] !in '0'..'9') fail("Invalid number literal", start)
            while (i < n && src[i] in '0'..'9') i++
            exp = src.substring(es, i)
        }
        if (i < n && isIdentStart(src[i])) fail("Invalid number literal", start)
        val literal = intPart + (if (frac.isNotEmpty()) ".$frac" else "") + exp
        return Token(Tk.NUMBER, literal, base + start)
    }

    private fun readString(): Token {
        val start = i
        i++ // opening quote
        val parts = ArrayList<StrPart>()
        val sb = StringBuilder()
        while (true) {
            if (i >= n) fail("Unterminated string", start)
            val c = src[i]
            if (c == '"') {
                i++
                break
            }
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            i++
            if (i >= n) fail("Unterminated string", start)
            when (val e = src[i]) {
                '(' -> {
                    if (sb.isNotEmpty()) {
                        parts += StrPart.Lit(sb.toString())
                        sb.clear()
                    }
                    val exprStart = i + 1
                    val end = findClosingParen(exprStart)
                    parts += StrPart.Interp(QueryParser(src.substring(exprStart, end), base + exprStart).parseProgram())
                    i = end + 1
                    continue
                }
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 >= n) fail("Invalid \\u escape")
                    val hex = src.substring(i + 1, i + 5)
                    val code = hex.toIntOrNull(16) ?: fail("Invalid \\u escape")
                    sb.append(code.toChar())
                    i += 4
                }
                else -> fail("Invalid escape '\\$e'")
            }
            i++
        }
        if (sb.isNotEmpty() || parts.isEmpty()) parts += StrPart.Lit(sb.toString())
        return Token(Tk.STRING, src.substring(start, i), base + start, parts)
    }

    /** Finds the ')' closing an interpolation that starts at [from], skipping nested strings. */
    private fun findClosingParen(from: Int): Int {
        var depth = 1
        var j = from
        while (j < n) {
            when (src[j]) {
                '"' -> {
                    j = skipStringLiteral(j)
                    continue
                }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        fail("Unterminated string interpolation", from)
    }

    private fun skipStringLiteral(start: Int): Int {
        var j = start + 1
        while (j < n) {
            val c = src[j]
            if (c == '"') return j + 1
            if (c == '\\') {
                if (j + 1 < n && src[j + 1] == '(') {
                    j = findClosingParen(j + 2) + 1
                    continue
                }
                j += 2
                continue
            }
            j++
        }
        fail("Unterminated string", start)
    }

    private companion object {
        // Longest first so that e.g. "//=" wins over "//" and "/".
        val OPERATORS = listOf(
            "?//", "//=", "|=", "+=", "-=", "*=", "/=", "%=", "==", "!=", "<=", ">=", "//",
            "|", ",", "(", ")", "[", "]", "{", "}", ":", ";", "?", "=", "<", ">", "+", "-", "*", "/", "%",
        )
    }
}

internal class QueryParser(src: String, private val base: Int = 0) {
    private val toks = QueryLexer(src, base).tokens()
    private var p = 0
    private var noComma = false

    private val tok get() = toks[p]
    private val peek get() = toks[minOf(p + 1, toks.size - 1)]

    private fun advance(): Token = toks[p].also { if (p < toks.size - 1) p++ }

    private fun fail(msg: String, t: Token = tok): Nothing = throw JsonQuerySyntaxException(msg, t.pos)

    private fun expect(punct: String) {
        if (!tok.isPunct(punct)) fail("Expected '$punct' but found $tok")
        advance()
    }

    private fun expectKeyword(k: String) {
        if (!tok.isKeyword(k)) fail("Expected '$k' but found $tok")
        advance()
    }

    private inline fun <T> commaMode(allowComma: Boolean, body: () -> T): T {
        val saved = noComma
        noComma = !allowComma
        try {
            return body()
        } finally {
            noComma = saved
        }
    }

    fun parseProgram(): Node {
        val node = parsePipe()
        if (tok.type != Tk.EOF) fail("Unexpected $tok")
        return node
    }

    private fun parsePipe(): Node {
        if (tok.isKeyword("def")) {
            val def = parseDef()
            return Node.FuncDef(def.name, def.params, def.body, parsePipe())
        }
        val left = parseComma()
        if (tok.isPunct("|")) {
            advance()
            return Node.Pipe(left, parsePipe())
        }
        return left
    }

    private class Def(val name: String, val params: List<String>, val body: Node)

    private fun parseDef(): Def {
        expectKeyword("def")
        if (tok.type != Tk.IDENT) fail("Expected a function name after 'def'")
        val name = advance().text
        val params = ArrayList<String>()
        if (tok.isPunct("(")) {
            advance()
            while (true) {
                params += when (tok.type) {
                    Tk.IDENT -> advance().text
                    Tk.VAR -> "$" + advance().text
                    else -> fail("Expected a parameter name")
                }
                if (tok.isPunct(";")) {
                    advance()
                    continue
                }
                expect(")")
                break
            }
        }
        expect(":")
        val body = commaMode(true) { parsePipe() }
        expect(";")
        return Def(name, params, body)
    }

    private fun parseComma(): Node {
        var left = parseAlt()
        while (!noComma && tok.isPunct(",")) {
            advance()
            left = Node.Comma(left, parseAlt())
        }
        return left
    }

    private fun parseAlt(): Node {
        val left = parseAssign()
        if (tok.isPunct("//")) {
            advance()
            return Node.Alt(left, parseAlt())
        }
        return left
    }

    private fun parseAssign(): Node {
        val left = parseOr()
        if (tok.type == Tk.PUNCT && tok.text in ASSIGN_OPS) {
            val op = advance().text
            return Node.Assign(op, left, parseAlt())
        }
        return left
    }

    private fun parseOr(): Node {
        var left = parseAnd()
        while (tok.isKeyword("or")) {
            advance()
            left = Node.Or(left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): Node {
        var left = parseCompare()
        while (tok.isKeyword("and")) {
            advance()
            left = Node.And(left, parseCompare())
        }
        return left
    }

    private fun parseCompare(): Node {
        val left = parseAdditive()
        if (tok.type == Tk.PUNCT && tok.text in COMPARE_OPS) {
            val op = advance().text
            val right = parseAdditive()
            if (tok.type == Tk.PUNCT && tok.text in COMPARE_OPS) fail("Comparison operators cannot be chained")
            return Node.Binary(op, left, right)
        }
        return left
    }

    private fun parseAdditive(): Node {
        var left = parseMultiplicative()
        while (tok.isPunct("+") || tok.isPunct("-")) {
            val op = advance().text
            left = Node.Binary(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): Node {
        var left = parseUnary()
        while (tok.isPunct("*") || tok.isPunct("/") || tok.isPunct("%")) {
            val op = advance().text
            left = Node.Binary(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): Node {
        if (tok.isPunct("-")) {
            advance()
            return Node.Neg(parseUnary())
        }
        return parsePostfix(allowAs = true)
    }

    private fun parsePostfix(allowAs: Boolean): Node {
        var node = parseTerm()
        while (true) {
            node = when {
                tok.type == Tk.FIELD -> Node.Index(node, Node.Literal(JsonString(advance().text)))
                tok.type == Tk.DOT && peek.type == Tk.STRING -> {
                    advance()
                    Node.Index(node, parseStringToken(null))
                }
                tok.type == Tk.DOT && peek.isPunct("[") -> {
                    advance()
                    continue
                }
                tok.isPunct("[") -> parseBracketSuffix(node)
                tok.isPunct("?") -> {
                    advance()
                    when (node) {
                        is Node.Index -> Node.Index(node.target, node.key, optional = true)
                        is Node.Slice -> Node.Slice(node.target, node.from, node.to, optional = true)
                        is Node.Iterate -> Node.Iterate(node.target, optional = true)
                        else -> Node.Try(node, null)
                    }
                }
                else -> break
            }
        }
        if (allowAs && tok.isKeyword("as")) {
            advance()
            val pattern = parsePattern()
            if (tok.isPunct("?//")) fail("Destructuring alternatives (?//) are not supported")
            expect("|")
            return Node.Bind(node, pattern, parsePipe())
        }
        return node
    }

    private fun parseBracketSuffix(target: Node): Node {
        expect("[")
        if (tok.isPunct("]")) {
            advance()
            return Node.Iterate(target)
        }
        return commaMode(true) {
            if (tok.isPunct(":")) {
                advance()
                val to = parsePipe()
                expect("]")
                Node.Slice(target, null, to)
            } else {
                val first = parsePipe()
                if (tok.isPunct(":")) {
                    advance()
                    val to = if (tok.isPunct("]")) null else parsePipe()
                    expect("]")
                    Node.Slice(target, first, to)
                } else {
                    expect("]")
                    Node.Index(target, first)
                }
            }
        }
    }

    private fun parseStringToken(format: String?): Node {
        val t = advance()
        val parts = t.parts!!
        if (format == null && parts.size == 1 && parts[0] is StrPart.Lit) {
            return Node.Literal(JsonString((parts[0] as StrPart.Lit).text))
        }
        return Node.Str(parts, format)
    }

    private fun parseTerm(): Node {
        val t = tok
        return when (t.type) {
            Tk.DOT -> {
                advance()
                if (tok.type == Tk.STRING) Node.Index(Node.Identity, parseStringToken(null)) else Node.Identity
            }
            Tk.DOTDOT -> {
                advance()
                Node.Call("recurse", emptyList(), t.pos)
            }
            Tk.FIELD -> {
                advance()
                Node.Index(Node.Identity, Node.Literal(JsonString(t.text)))
            }
            Tk.NUMBER -> {
                advance()
                Node.Literal(JsonNumber.parseOrNull(t.text) ?: fail("Invalid number literal", t))
            }
            Tk.STRING -> parseStringToken(null)
            Tk.FORMAT -> {
                advance()
                if (tok.type == Tk.STRING) parseStringToken(t.text) else Node.Format(t.text)
            }
            Tk.VAR -> {
                advance()
                if (t.text == "__loc__") {
                    Node.Literal(com.fajarnuha.kson.jsonObjectOf("file" to "<top-level>", "line" to 1))
                } else {
                    Node.Var(t.text)
                }
            }
            Tk.PUNCT -> when (t.text) {
                "(" -> {
                    advance()
                    val inner = commaMode(true) { parsePipe() }
                    expect(")")
                    inner
                }
                "[" -> {
                    advance()
                    if (tok.isPunct("]")) {
                        advance()
                        Node.ArrayCons(null)
                    } else {
                        val body = commaMode(true) { parsePipe() }
                        expect("]")
                        Node.ArrayCons(body)
                    }
                }
                "{" -> parseObject()
                else -> fail("Unexpected $t")
            }
            Tk.IDENT -> when (t.text) {
                "null" -> advance().let { Node.Literal(com.fajarnuha.kson.JsonNull) }
                "true" -> advance().let { Node.Literal(com.fajarnuha.kson.JsonBool.True) }
                "false" -> advance().let { Node.Literal(com.fajarnuha.kson.JsonBool.False) }
                "if" -> parseIf()
                "try" -> {
                    advance()
                    val body = parsePostfix(allowAs = false)
                    val handler = if (tok.isKeyword("catch")) {
                        advance()
                        parsePostfix(allowAs = false)
                    } else {
                        null
                    }
                    Node.Try(body, handler)
                }
                "reduce" -> {
                    advance()
                    val source = parsePostfix(allowAs = false)
                    expectKeyword("as")
                    val pattern = parsePattern()
                    expect("(")
                    val (init, update) = commaMode(true) {
                        val init = parsePipe()
                        expect(";")
                        init to parsePipe()
                    }
                    expect(")")
                    Node.Reduce(source, pattern, init, update)
                }
                "foreach" -> {
                    advance()
                    val source = parsePostfix(allowAs = false)
                    expectKeyword("as")
                    val pattern = parsePattern()
                    expect("(")
                    val node = commaMode(true) {
                        val init = parsePipe()
                        expect(";")
                        val update = parsePipe()
                        val extract = if (tok.isPunct(";")) {
                            advance()
                            parsePipe()
                        } else {
                            null
                        }
                        Node.Foreach(source, pattern, init, update, extract)
                    }
                    expect(")")
                    node
                }
                "def" -> {
                    val def = parseDef()
                    Node.FuncDef(def.name, def.params, def.body, parsePipe())
                }
                "label", "import", "include" -> fail("'${t.text}' is not supported")
                in KEYWORDS -> fail("Unexpected keyword '${t.text}'")
                else -> {
                    advance()
                    val args = ArrayList<Node>()
                    if (tok.isPunct("(")) {
                        advance()
                        commaMode(true) {
                            while (true) {
                                args += parsePipe()
                                if (tok.isPunct(";")) {
                                    advance()
                                    continue
                                }
                                break
                            }
                        }
                        expect(")")
                    }
                    Node.Call(t.text, args, t.pos)
                }
            }
            Tk.EOF -> fail("Unexpected end of filter")
        }
    }

    private fun parseIf(): Node {
        expectKeyword("if")
        val cond = commaMode(true) { parsePipe() }
        expectKeyword("then")
        val then = commaMode(true) { parsePipe() }
        val otherwise: Node? = when {
            tok.isKeyword("elif") -> {
                // Rewrite `elif` as a nested `if` that shares this `end`.
                parseElif()
            }
            tok.isKeyword("else") -> {
                advance()
                commaMode(true) { parsePipe() }.also { expectKeyword("end") }
            }
            else -> {
                expectKeyword("end")
                null
            }
        }
        return Node.If(cond, then, otherwise)
    }

    /** Parses `elif c then b ... end`, consuming the final `end`. */
    private fun parseElif(): Node {
        expectKeyword("elif")
        val cond = commaMode(true) { parsePipe() }
        expectKeyword("then")
        val then = commaMode(true) { parsePipe() }
        val otherwise: Node? = when {
            tok.isKeyword("elif") -> parseElif()
            tok.isKeyword("else") -> {
                advance()
                commaMode(true) { parsePipe() }.also { expectKeyword("end") }
            }
            else -> {
                expectKeyword("end")
                null
            }
        }
        return Node.If(cond, then, otherwise)
    }

    private fun parseObject(): Node {
        expect("{")
        val entries = ArrayList<ObjEntry>()
        if (tok.isPunct("}")) {
            advance()
            return Node.ObjectCons(entries)
        }
        commaMode(true) {
            while (true) {
                entries += parseObjectEntry()
                if (tok.isPunct(",")) {
                    advance()
                    continue
                }
                expect("}")
                break
            }
        }
        return Node.ObjectCons(entries)
    }

    private fun parseObjectValue(): Node = commaMode(false) { parsePipe() }

    private fun parseObjectEntry(): ObjEntry {
        val t = tok
        return when {
            t.type == Tk.VAR -> {
                advance()
                val key = Node.Literal(JsonString(t.text))
                if (tok.isPunct(":")) {
                    advance()
                    ObjEntry(key, parseObjectValue())
                } else {
                    ObjEntry(key, Node.Var(t.text))
                }
            }
            t.type == Tk.IDENT || t.type == Tk.NUMBER -> {
                if (t.type == Tk.NUMBER) fail("Object keys must be strings; quote the key or wrap it in parentheses")
                advance()
                val key = Node.Literal(JsonString(t.text))
                if (tok.isPunct(":")) {
                    advance()
                    ObjEntry(key, parseObjectValue())
                } else {
                    ObjEntry(key, Node.Index(Node.Identity, key))
                }
            }
            t.type == Tk.STRING || t.type == Tk.FORMAT -> {
                val key = if (t.type == Tk.FORMAT) {
                    advance()
                    if (tok.type != Tk.STRING) fail("Expected a string after @${t.text}")
                    parseStringToken(t.text)
                } else {
                    parseStringToken(null)
                }
                if (tok.isPunct(":")) {
                    advance()
                    ObjEntry(key, parseObjectValue())
                } else {
                    ObjEntry(key, Node.Index(Node.Identity, key))
                }
            }
            t.isPunct("(") -> {
                advance()
                val key = commaMode(true) { parsePipe() }
                expect(")")
                expect(":")
                ObjEntry(key, parseObjectValue())
            }
            else -> fail("Unexpected $t in object construction")
        }
    }

    private fun parsePattern(): Pattern {
        val t = tok
        return when {
            t.type == Tk.VAR -> {
                advance()
                Pattern.Var(t.text)
            }
            t.isPunct("[") -> {
                advance()
                val elements = ArrayList<Pattern>()
                if (!tok.isPunct("]")) {
                    while (true) {
                        elements += parsePattern()
                        if (tok.isPunct(",")) {
                            advance()
                            continue
                        }
                        break
                    }
                }
                expect("]")
                Pattern.Arr(elements)
            }
            t.isPunct("{") -> {
                advance()
                val entries = ArrayList<ObjPatternEntry>()
                while (true) {
                    val e = tok
                    entries += when {
                        e.type == Tk.VAR -> {
                            advance()
                            val sub = if (tok.isPunct(":")) {
                                advance()
                                parsePattern()
                            } else {
                                null
                            }
                            ObjPatternEntry(Node.Literal(JsonString(e.text)), e.text, sub)
                        }
                        e.type == Tk.IDENT -> {
                            advance()
                            expect(":")
                            ObjPatternEntry(Node.Literal(JsonString(e.text)), null, parsePattern())
                        }
                        e.type == Tk.STRING -> {
                            val key = parseStringToken(null)
                            expect(":")
                            ObjPatternEntry(key, null, parsePattern())
                        }
                        e.isPunct("(") -> {
                            advance()
                            val key = commaMode(true) { parsePipe() }
                            expect(")")
                            expect(":")
                            ObjPatternEntry(key, null, parsePattern())
                        }
                        else -> fail("Unexpected $e in object pattern")
                    }
                    if (tok.isPunct(",")) {
                        advance()
                        continue
                    }
                    break
                }
                expect("}")
                Pattern.Obj(entries)
            }
            else -> fail("Expected a pattern (\$name, [...] or {...}) but found $t")
        }
    }

    private companion object {
        val ASSIGN_OPS = setOf("=", "|=", "+=", "-=", "*=", "/=", "%=", "//=")
        val COMPARE_OPS = setOf("==", "!=", "<", "<=", ">", ">=")
        val KEYWORDS = setOf("then", "elif", "else", "end", "as", "catch", "and", "or", "reduce", "foreach", "if", "try", "def")
    }
}
