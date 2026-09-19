# kson

A small, dependency-free JSON toolkit for Kotlin Multiplatform:

- **`kson-core`**: a library with a JSON value model, a builder DSL, a strict RFC 8259 parser and writer, typed accessors, JSON Pointer, a jq-compatible query engine, and JSON Schema inference.
- **`kson-cli`**: a Kotlin/Native command-line tool (`kson`) that works like jq: `cat a.json | kson` prints colored JSON, and `kson '<filter>'` runs jq filters.

Targets: JVM, macOS (arm64/x64), Linux (x64/arm64), Windows (mingwX64), iOS (arm64/simulator/x64).

## Builder DSL

```kotlin
import com.fajarnuha.kson.*

val user = json {
    "id" to 42
    "name" to "Fajar"
    "active" to true
    "nickname" to null
    "role" to Role.ADMIN                         // enums become their name
    "address" to {                               // lambda form
        "city" to "Tangerang Selatan"
        "geo" { "lat" to -6.29; "lng" to 106.72 } // invoke form
    }
    "tags" to arr("kotlin", "android")           // flat array
    "devices" to arr {                           // block array
        obj { "os" to "android"; "v" to 15 }
        +"raw string element"
        add(3)                                   // use add() for numbers: +3 is Int.unaryPlus
    }
}

println(user)                      // compact
println(user.toJson(pretty = true))

val list = jsonArray { add(1); +"two"; obj { "three" to 3 } }
```

Existing Kotlin data drops straight in. `Map`, `Iterable`, `Sequence`, arrays, primitive arrays, `Pair`, enums, unsigned numbers and `Char` are all converted.

```kotlin
val config = mapOf("retries" to 3, "hosts" to listOf("a", "b")) // build maps OUTSIDE the block
val doc = json { "config" to config }
```

> Inside `json { }`, `to` is the DSL's field setter and shadows `kotlin.to`. Build `Pair`s and maps outside the block, or use `put(key, value)` / `putAll(map)`.

Other helpers: `put`, `putAll`, `putIfNotNull`, `remove`, `jsonObjectOf(...)`, `jsonArrayOf(...)`, and `obj.buildUpon { ... }` to copy and extend.

## Parsing and writing

```kotlin
val v: JsonValue = Json.parse("""{"a":[1,2.5,null]}""")   // throws JsonParseException(line, column, offset)
val maybe = "not json".parseJsonOrNull()                  // null
Json.isValid(text)
Json.minify(text); Json.prettify(text, indent = "    ")

v.toJson()                                                // compact
v.toJson(JsonFormat(pretty = true, indent = "\t", sortKeys = true, escapeNonAscii = true))
```

The parser follows RFC 8259 exactly, like `JSON.parse`. It rejects trailing commas, comments, single quotes, leading zeros, `NaN` and `Infinity`, and unescaped control characters. It accepts any value at the top level and tolerates a leading byte-order mark. Duplicate keys keep the last value by default; `JsonParseOptions.Strict` rejects them. Nesting is capped at `maxDepth` (512 by default).

**Numbers keep their exact text.** `JsonNumber` stores the literal, so `12345678901234567890` or `0.1000000000000000055511151231257827` round-trip unchanged. Equality is by value: `1`, `1.0` and `1e0` are equal. `JsonNumber` is itself a `Number`.

## Reading values

```kotlin
v["a"][1]?.double           // chained lookups return null instead of throwing
v["a"][0]!!.int             // typed accessors throw JsonTypeException on mismatch
v["missing"]?.stringOrNull  // ...OrNull variants return null
v.at("/a/0")                // RFC 6901 JSON Pointer
v.toKotlin()                // back to Map / List / String / Long / Double / Boolean / null
```

Accessors: `string`, `int`, `long`, `double`, `float`, `boolean`, `number`, `jsonObject`, `jsonArray`, each with an `OrNull` variant, plus `isNull` and `jsonType`.

Transformations on the immutable model: `obj + other`, `obj + ("k" to v)`, `obj - "k"`, `deepMerge`, `sortedKeys()`, `withoutNulls()`, and `walk { pointer, value -> }`.

## jq queries

`kson-core` runs programs written in the jq language, so the same filter works in code and in the terminal.

```kotlin
val doc = Json.parse(text)
val names: List<JsonValue> = doc.query(".users[] | select(.age > 30) | .name")
val first: JsonValue? = doc.queryFirst(".users[0]")

// Compile once, run many times; bind $variables.
val q = JsonQuery.compile("[.[] | select(.score >= \$min)] | length")
q.all(doc, variables = mapOf("min" to JsonNumber(50)))
q.run(doc) { output -> println(output) }   // streaming
```

The supported language includes:

- **Paths and iteration.** `.a.b`, `."key"`, `.[0]`, `.[-1]`, `.[2:4]`, `.[]`, `..`, and the optional `?` suffix.
- **Operators.** Pipes and commas, arithmetic on numbers, strings, arrays and objects, comparisons, `and`, `or`, `not`, and the `//` alternative.
- **Construction.** Arrays, objects with computed keys and shorthand like `{a, $x}`, string interpolation, and `@base64`, `@csv`, `@tsv`, `@sh`, `@uri`, `@html`, `@json`.
- **Control flow.** `if`/`elif`/`else`, `try`/`catch`, `reduce`, `foreach`, `limit`, `first`, `until`, `while` and `repeat`.
- **Definitions.** `def` with filter and `$value` parameters, recursion, variables, and destructuring such as `. as {a: $x, b: [$y]}`.
- **Assignment.** `=`, `|=`, `+=`, `-=`, `*=`, `/=`, `%=`, `//=`, and path functions like `path`, `paths`, `getpath`, `setpath`, `del`, `delpaths`, `pick`, `to_entries`, `with_entries`.
- **The builtin library.** This covers `map`, `select`, `keys`, `has`, `length`, `sort_by`, `group_by`, `unique_by`, `min_by`, `add`, `any`, `all`, `flatten`, `range`, `walk`, `transpose`, `tostream`, `split`, `join`, `ascii_downcase`, `ltrimstr`, `test`, `match`, `capture`, `scan`, `sub`, `gsub`, `splits`, `tojson`, `fromjson`, `tonumber`, `input`, `inputs`, `debug`, `$ENV` and more.

### Compatibility with jq

`scripts/jq-parity.sh` runs more than 350 filters through both jq 1.7.1 and kson and compares the output byte for byte. CI runs it on every push. The deliberate differences are:

- **Number literals keep their spelling.** jq 1.7 rewrites `1e3` as `1E+3`, while kson prints the literal as written. Computed numbers print exactly like jq, for example `1e-07` or `0.30000000000000004`.
- **Integer arithmetic is exact.** Sums and products of 64-bit integers do not lose precision above 2^53, as they do in jq.
- **A few newer builtins exist.** `trim`, `ltrim`, `rtrim`, `toarray`, `abs`, `add(f)`, `leaf_paths`, `reverse` on strings, and `repeat` follow jq 1.8 or the jq manual where jq 1.7.1 lacks or mishandles them.
- **Not supported.** Modules (`import`, `include`), `label`/`break`, `?//` destructuring alternatives, `fromstream`, `truncate_stream`, and the date functions (`now`, `strftime`, `mktime`).

## JSON Schema

```kotlin
val schema: String = user.toJsonSchema()                       // draft 2020-12, pretty-printed
val schemaObj: JsonObject = user.toJsonSchemaValue()
inferJsonSchema("""[{"a":1},{"a":2.5,"b":"x"}]""")

user.toJsonSchema(JsonSchemaOptions(
    title = "User",
    additionalProperties = false,   // closed objects
    requireAllProperties = true,    // properties present in every sample are required
    detectFormats = true,           // date-time, date, time, uuid, email, ipv4, uri
))
```

Inference rules:

- Integral numbers become `integer` and the rest become `number`.
- Array items are merged across every element. Mixed types produce a `type` array. Object elements merge their properties, and only keys present in every element stay `required`.
- A string `format` is kept only when all merged samples agree on it.

## CLI

`kson` behaves like jq. With no arguments it pretty-prints its input, and its first argument is a jq filter unless it names a subcommand.

```bash
cat a.json | kson                                  # pretty-print with colors
curl -s https://api.example.com/users | kson '.[] | {name, email}'
kson -r '.items[].id' a.json b.json                # raw strings, several files
kson -c 'map(select(.active))' users.json          # compact output
kson -n --arg env prod '{env: $env, debug: false}'  # build JSON from scratch
cat events.ndjson | kson -c 'select(.level == "error")'
kson -s 'map(.size) | add' *.json                  # slurp all inputs into one array
```

Colors are on when stdout is a terminal and off when output is piped. `-C` forces them, and `-M` or `NO_COLOR=1` turns them off. The palette follows jq and can be changed with `JQ_COLORS`.

The jq flags work the same way:

| Flag | Meaning |
|---|---|
| `-r`, `-j` | Raw string output, and raw output without newlines |
| `-c`, `--indent n`, `--tab` | Compact output or custom indentation |
| `-S`, `-a` | Sort keys, escape non-ASCII |
| `-C`, `-M` | Force or disable colors |
| `-n`, `-s`, `-R` | Null input, slurp all inputs, raw text lines as input |
| `-e` | Set the exit status from the last output |
| `--arg`, `--argjson` | Bind `$name` to a string or JSON value |

Short flags combine, as in `-rc`. Input may contain several JSON values, such as newline-delimited JSON, and the filter runs once per value.

Exit codes in query mode match jq:

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | With `-e`, the last output was `false` or `null` |
| 2 | Usage error or invalid input JSON |
| 3 | The filter does not compile |
| 4 | With `-e`, there was no output |
| 5 | The filter raised an error |

Subcommands cover tasks jq has no shortcut for:

```
kson fmt data.json                 # pretty-print
kson min data.json                 # minify
kson validate data.json            # exit 1 with file:line:column on error
kson get /users/0/name -r data.json   # RFC 6901 JSON Pointer
kson keys /users/0 data.json
kson type /users data.json
kson paths data.json               # every JSON Pointer
kson schema --title User data.json # infer a JSON Schema
kson merge base.json override.json # deep merge
kson build name=Fajar age:=42 address.city=Tangsel tags:='["a","b"]'
kson build -a one :=2 ':={"three":3}'
```

Subcommands exit with 0 for success, 1 for invalid JSON or a missing pointer, and 2 for usage errors.

### Building the CLI

```bash
./gradlew :kson-cli:linkReleaseExecutableMacosArm64   # also MacosX64, LinuxX64, LinuxArm64, MingwX64
cp kson-cli/build/bin/macosArm64/releaseExecutable/kson.kexe /usr/local/bin/kson
```

Linking the macOS binaries needs a full Xcode install. The Linux and Windows binaries cross-compile from any host. CI uploads a binary for every platform as a build artifact.

## Using the library in another project

**Option 1: composite build.** This needs no publishing. In the consumer's `settings.gradle.kts`:

```kotlin
includeBuild("../kson")
```

```kotlin
// build.gradle.kts, in commonMain / main dependencies
implementation("com.fajarnuha.kson:kson-core:0.1.0")
```

**Option 2: Maven local.**

```bash
./gradlew :kson-core:publishToMavenLocal
```

Then add `mavenLocal()` to the consumer's repositories.

**Option 3: GitHub Packages.** Pushing a `v*` tag publishes the library there. Consumers need a token with `read:packages`:

```kotlin
repositories {
    maven("https://maven.pkg.github.com/fajarnuha/kson") {
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()
        }
    }
}
```

## Development

```bash
./gradlew :kson-core:jvmTest          # includes a parity suite checked against Jackson
./gradlew :kson-core:allTests :kson-cli:allTests

# jq parity: build the CLI, then compare against a jq 1.7.1 binary
./gradlew :kson-cli:linkReleaseExecutableLinuxX64
scripts/jq-parity.sh kson-cli/build/bin/linuxX64/releaseExecutable/kson.kexe
```

The common tests run on every target. The JVM-only `JacksonParityTest` checks that kson accepts, rejects and round-trips the same corpus as Jackson in strict mode. `scripts/jq-parity-cases.txt` holds the jq comparison cases, one `flags ::: filter ::: input` per line.
