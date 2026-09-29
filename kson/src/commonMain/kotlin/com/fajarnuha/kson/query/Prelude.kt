package com.fajarnuha.kson.query

/**
 * Builtins that jq itself defines in jq. Order matters: a definition can only use the ones above it.
 */
internal object Prelude {
    private const val SOURCE = $$"""
def select(f): if f then . else empty end;
def values: select(. != null);
def nulls: select(. == null);
def booleans: select(type == "boolean");
def numbers: select(type == "number");
def strings: select(type == "string");
def arrays: select(type == "array");
def objects: select(type == "object");
def iterables: select(type | . == "array" or . == "object");
def scalars: select(type | . != "array" and . != "object");
def map(f): [.[] | f];
def recurse(f): def r: ., (f | r); r;
def recurse(f; cond): def r: ., (f | select(cond) | r); r;
def to_entries: [keys_unsorted[] as $k | {key: $k, value: .[$k]}];
def from_entries: reduce .[] as $x ({}; . + { ($x | .key // .name): $x.value });
def with_entries(f): to_entries | map(f) | from_entries;
def map_values(f): .[] |= f;
def del(f): delpaths([path(f)]);
def paths: path(..) | select(length > 0);
def paths(node_filter): . as $dot | paths | select(. as $p | $dot | getpath($p) | node_filter);
def leaf_paths: paths(scalars);
def isempty(g): first((g | false), true);
def any: reduce .[] as $x (false; . or $x);
def all: reduce .[] as $x (true; . and $x);
def any(f): reduce (.[] | f) as $x (false; . or $x);
def all(f): reduce (.[] | f) as $x (true; . and $x);
def any(g; cond): isempty(first(g | cond | select(.))) | not;
def all(g; cond): isempty(first(g | cond | select(. | not)));
def in(xs): . as $x | xs | has($x);
def inside(xs): . as $x | xs | contains($x);
def walk(f): def w: if type == "object" then map_values(w) elif type == "array" then map(w) else . end | f; w;
def first: .[0];
def last: .[-1];
def nth($n): .[$n];
def last(f): reduce f as $x (null; $x);
def nth($n; f): if $n < 0 then error("Out of bounds negative array index") else last(limit($n + 1; f)) end;
def until(cond; update): def _until: if cond then . else (update | _until) end; _until;
def while(cond; update): def _while: if cond then ., (update | _while) else empty end; _while;
def repeat(f): def _repeat: ., (f | _repeat); _repeat;
def range($x): range(0; $x);
def add: reduce .[] as $x (null; . + $x);
def add(f): reduce f as $x (null; . + $x);
def index($i): indices($i) | .[0];
def rindex($i): indices($i) | .[-1:][0];
def transpose: if . == [] then [] else . as $in | (map(length) | max) as $max
    | [range(0; $max) as $j | [range(0; $in | length) as $i | $in[$i][$j]]] end;
def IN(s): any(s == .; .);
def IN(src; s): any(src == s; .);
def INDEX(stream; idx_expr): reduce stream as $row ({}; .[$row | idx_expr | tostring] |= $row);
def INDEX(idx_expr): INDEX(.[]; idx_expr);
def pick(pathexps): . as $top | reduce path(pathexps) as $p (null; setpath($p; $top | getpath($p)));
def debug(msg): (msg | debug | empty), .;
def toarray: if type == "array" then . else [.] end;
def combinations: if length == 0 then [] else .[0][] as $x | (.[1:] | combinations) as $w | [$x] + $w end;
def combinations(n): . as $dot | [range(n)] | map($dot) | combinations;
def halt_error: error;
def significand: if . == 0 then 0 else . as $x | ($x | fabs | log2 | floor) as $e | $x / pow(2; $e) end;
def finites: select(isinfinite | not);
def normals: select(. != 0 and (isinfinite | not));
"""

    /** The prelude definitions as nested [Node.FuncDef]s, ending in `.`. */
    private class Compiled(val program: Node, val keys: Set<String>)

    private val compiled: Compiled by lazy {
        val program = QueryParser("$SOURCE .").parseProgram()
        val keys = LinkedHashSet<String>()
        var n = program
        while (n is Node.FuncDef) {
            keys += "${n.name}/${n.params.size}"
            n = n.rest
        }
        Checker.check(program, keys)
        Compiled(program, keys)
    }

    val program: Node get() = compiled.program

    /** `name/arity` of every prelude function. */
    val keys: Set<String> get() = compiled.keys

    /** Builds the environment holding every prelude function. */
    fun env(interpreter: Interpreter): Env {
        var env = Env.Empty
        var n = program
        while (n is Node.FuncDef) {
            env = interpreter.define(n, env)
            n = n.rest
        }
        return env
    }
}
