# kson

A JSON toolkit for Kotlin Multiplatform:

- `kson-core` provides JSON values, a builder DSL, parsing, writing, JSON Pointer, jq-compatible queries, and JSON Schema inference.
- `kson-cli` provides the native `kson` command for formatting JSON and running jq filters.
- `kson-playground` is a JVM app showing KSON with Ktor and Retrofit HTTP clients.

Targets: JVM, macOS (arm64/x64), Linux (x64/arm64), Windows (mingwX64), iOS (arm64/simulator/x64).

## Builder DSL

```kotlin
val user = json {
    "id" to 42
    "name" to "Sample"
    "active" to true
    "nickname" to null
    "role" to Role.ADMIN                         // enums become their name
    "address" to {                               // lambda form
        "city" to "Example City"
        "point" { "x" to 12.34; "y" to 56.78 }  // invoke form
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

The builder accepts `Map`, `Iterable`, `Sequence`, arrays, primitive arrays, `Pair`, enums, unsigned numbers, and `Char`.

```kotlin
val config = mapOf("retries" to 3, "hosts" to listOf("a", "b"))
val doc = json { "config" to config }
```

Inside `json { }`, `to` sets a field and shadows `kotlin.to`. Build pairs and maps before the block, or use `put(key, value)` and `putAll(map)`.

Other helpers include `putIfNotNull`, `remove`, `jsonObjectOf(...)`, `jsonArrayOf(...)`, and `obj.buildUpon { ... }`.

## Parsing and writing

```kotlin
val v: JsonValue = Json.parse("""{"a":[1,2.5,null]}""")   // throws JsonParseException(line, column, offset)
val maybe = "not json".parseJsonOrNull()                  // null
Json.isValid(text)
Json.minify(text); Json.prettify(text, indent = "    ")

v.toJson()                                                // compact
v.toJson(JsonFormat(pretty = true, indent = "\t", sortKeys = true, escapeNonAscii = true))
```

The parser follows RFC 8259. It rejects trailing commas, comments, single quotes, leading zeros, `NaN`, `Infinity`, and unescaped control characters. It accepts any top-level JSON value and a leading byte-order mark. By default, the last duplicate key wins. `JsonParseOptions.Strict` rejects duplicate keys. The default nesting limit is 512.

`JsonNumber` keeps the original literal, so `12345678901234567890` and `0.1000000000000000055511151231257827` round-trip unchanged. Equality uses numeric value: `1`, `1.0`, and `1e0` are equal. `JsonNumber` extends `Number`.

## Reading values

```kotlin
v["a"][1]?.double           // chained lookups return null instead of throwing
v["a"][0]!!.int             // typed accessors throw JsonTypeException on mismatch
v["missing"]?.stringOrNull  // ...OrNull variants return null
v.at("/a/0")                // RFC 6901 JSON Pointer
v.toKotlin()                // back to Map / List / String / Long / Double / Boolean / null
```

Typed accessors include `string`, `int`, `long`, `double`, `float`, `boolean`, `number`, `jsonObject`, and `jsonArray`. Each has an `OrNull` variant. Use `isNull` and `jsonType` to inspect a value.

The immutable model supports `obj + other`, `obj + ("k" to v)`, `obj - "k"`, `deepMerge`, `sortedKeys()`, `withoutNulls()`, and `walk { pointer, value -> }`.

## jq queries

`kson-core` runs jq filters in Kotlin. The CLI accepts the same filters.

```kotlin
val doc = Json.parse(text)
val names: List<JsonValue> = doc.query(".users[] | select(.age > 30) | .name")
val first: JsonValue? = doc.queryFirst(".users[0]")

// Compile once, run many times; bind $variables.
val q = JsonQuery.compile("[.[] | select(.score >= \$min)] | length")
q.all(doc, variables = mapOf("min" to JsonNumber(50)))
q.run(doc) { output -> println(output) }   // streaming
```

Supported syntax includes:

- **Paths and iteration.** `.a.b`, `."key"`, `.[0]`, `.[-1]`, `.[2:4]`, `.[]`, `..`, and the optional `?` suffix.
- **Operators.** Pipes and commas, arithmetic on numbers, strings, arrays and objects, comparisons, `and`, `or`, `not`, and the `//` alternative.
- **Construction.** Arrays, objects with computed keys and shorthand like `{a, $x}`, string interpolation, and `@base64`, `@csv`, `@tsv`, `@sh`, `@uri`, `@html`, `@json`.
- **Control flow.** `if`/`elif`/`else`, `try`/`catch`, `reduce`, `foreach`, `limit`, `first`, `until`, `while` and `repeat`.
- **Definitions.** `def` with filter and `$value` parameters, recursion, variables, and destructuring such as `. as {a: $x, b: [$y]}`.
- **Assignment.** `=`, `|=`, `+=`, `-=`, `*=`, `/=`, `%=`, `//=`, and path functions like `path`, `paths`, `getpath`, `setpath`, `del`, `delpaths`, `pick`, `to_entries`, `with_entries`.
- **Builtins.** `map`, `select`, `keys`, `has`, `length`, `sort_by`, `group_by`, `unique_by`, `min_by`, `add`, `any`, `all`, `flatten`, `range`, `walk`, `transpose`, `tostream`, `split`, `join`, `ascii_downcase`, `ltrimstr`, `test`, `match`, `capture`, `scan`, `sub`, `gsub`, `splits`, `tojson`, `fromjson`, `tonumber`, `input`, `inputs`, `debug`, and `$ENV`.

### Compatibility with jq

`scripts/jq-parity.sh` compares more than 350 filters against jq 1.7.1 byte for byte. CI runs it on every push. Known differences are:

- **Number literals keep their spelling.** jq 1.7 rewrites `1e3` as `1E+3`, while kson prints the literal as written. Computed numbers print exactly like jq, for example `1e-07` or `0.30000000000000004`.
- **Integer arithmetic is exact.** Sums and products of 64-bit integers do not lose precision above 2^53, as they do in jq.
- **Newer builtins.** `trim`, `ltrim`, `rtrim`, `toarray`, `abs`, `add(f)`, `leaf_paths`, `reverse` on strings, and `repeat` follow jq 1.8 or the jq manual where jq 1.7.1 lacks or mishandles them.
- **Unsupported syntax.** Modules (`import`, `include`), `label`/`break`, `?//` destructuring alternatives, `fromstream`, `truncate_stream`, and the date functions (`now`, `strftime`, `mktime`).

## JSON Schema

```kotlin
val schema: String = user.toJsonSchema()                       // draft 2020-12, formatted
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

Define or refine a schema in Kotlin when a sample cannot tell you which fields are optional:

```kotlin
val todoSchema = jsonSchema {
    title("Todo")
    property("id", required = true) { type("integer"); minimum(0) }
    property("title", required = true) { type("string"); minLength(1) }
    property("tags") { items { type("string") } }
    additionalProperties(false)
}
println(todoSchema.toJson(pretty = true))
```

`jsonSchema {}` emits draft 2020-12 JSON Schema. Use `schema {}` for subschemas in `oneOf`, `anyOf`, or `allOf`. The builder also has `definition`, `ref`, `enum`, `format`, `pattern`, `const`, `default`, and common size and range keywords. It builds a schema; it does not validate response data.

## CLI

On macOS, install the release binary with Homebrew:

```bash
brew install fajarnuha/kson/kson
kson --version
```

Homebrew adds `kson` to its `bin` directory. For manual installation, download the binary for your system from [GitHub Releases](https://github.com/fajarnuha/kson/releases), rename it to `kson`, make it executable, and place it in a directory on your `PATH`.

With no arguments, `kson` formats JSON from stdin. Its first argument is a jq filter unless it names a subcommand.

```bash
cat a.json | kson                                  # pretty-print with colors
curl -s https://api.example.com/users | kson '.[] | {name, email}'
kson -r '.items[].id' a.json b.json                # raw strings, several files
kson -c 'map(select(.active))' users.json          # compact output
kson -n --arg env prod '{env: $env, debug: false}'  # build JSON from scratch
cat events.ndjson | kson -c 'select(.level == "error")'
kson -s 'map(.size) | add' *.json                  # slurp all inputs into one array
```

The CLI uses colors when stdout is a terminal. `-C` forces colors; `-M` or `NO_COLOR=1` disables them. Set `JQ_COLORS` to change the jq-style palette.

Query flags:

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

Subcommands:

```
kson fmt data.json                 # pretty-print
kson convert data.json             # generate Kotlin builder code for the same JSON value
kson min data.json                 # minify
kson validate data.json            # exit 1 with file:line:column on error
kson get /users/0/name -r data.json   # RFC 6901 JSON Pointer
kson keys /users/0 data.json
kson type /users data.json
kson paths data.json               # every JSON Pointer
kson schema --title User data.json # infer a JSON Schema
kson merge base.json override.json # deep merge
kson build name=Sample age:=42 address.city=ExampleCity tags:='["a","b"]'
kson build -a one :=2 ':={"three":3}'
```

Subcommands exit with 0 for success, 1 for invalid JSON or a missing pointer, and 2 for usage errors.

`convert` reads one JSON value from a file or stdin and prints a Kotlin expression using the `kson-core` builder DSL. Import `com.fajarnuha.kson.*` when using the generated code. Numbers that cannot be written as exact `Int` literals use `JsonNumber.parse(...)` to keep their original JSON text.

### Build the CLI

```bash
./gradlew :kson-cli:linkReleaseExecutableMacosArm64   # also MacosX64, LinuxX64, LinuxArm64, MingwX64
./kson-cli/build/bin/macosArm64/releaseExecutable/kson.kexe --version
```

macOS linking needs Xcode. CI uploads binaries for all five CLI targets.

Run `./gradlew bumpVersion` to bump the minor version. Use `-Ppart=patch` or `-Ppart=major` for other bumps. Commit the version changes, then push a matching tag. CI tests the tag and attaches binaries for all five targets, plus macOS Homebrew archives, to a GitHub Release.

## JVM network playground

The playground fetches a JSON response and reads it as a KSON `JsonValue`. The [Ktor example](kson-playground/src/main/kotlin/com/fajarnuha/kson/playground/KtorExample.kt) calls `bodyAsText()` and `Json.parse(...)`. The [Retrofit example](kson-playground/src/main/kotlin/com/fajarnuha/kson/playground/RetrofitExample.kt) installs a converter so its service returns `Call<JsonValue>` directly. Both use `https://jsonplaceholder.typicode.com/todos/1` by default.

```bash
./gradlew :kson-playground:run                         # run both clients
./gradlew :kson-playground:run --args='ktor'           # Ktor only
./gradlew :kson-playground:run --args='retrofit'       # Retrofit only
./gradlew :kson-playground:run --args='ktor https://example.com/data.json'
./gradlew :kson-playground:test                        # local HTTP test; no public service required
```

## Using the library in another project

### Composite build

Add the repository as an included build in the consumer's `settings.gradle.kts`:

```kotlin
includeBuild("../kson")
```

```kotlin
// build.gradle.kts, in commonMain or main dependencies
implementation("<group>:kson-core:<version>")
```

Use the `GROUP` and `VERSION_NAME` values from this repository's `gradle.properties` for the placeholders.

### Maven local for JVM

```bash
./gradlew :kson-core:publishJvmPublicationToMavenLocal
```

Add `mavenLocal()` to the consumer's repositories.

### GitHub Packages

Pushing a `v*` tag publishes the library to GitHub Packages. Consumers need a token with `read:packages`:

```kotlin
repositories {
    maven("https://maven.pkg.github.com/<owner>/kson") {
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()
        }
    }
}
```

Replace `<owner>` with the GitHub account that owns the repository.

### JitPack

CI requests a JitPack build for each pushed tag. Add JitPack to the consumer's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

For a JVM app, add the dependency in the module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.<owner>:kson:<tag>")
}
```

Replace `<owner>` with the GitHub account and `<tag>` with a pushed tag. This artifact contains the JVM library.

## Development

```bash
./gradlew :kson-core:jvmTest          # includes a parity suite checked against Jackson
./gradlew :kson-core:allTests :kson-cli:allTests

# jq parity: build the CLI, then compare against a jq 1.7.1 binary
./gradlew :kson-cli:linkReleaseExecutableLinuxX64
scripts/jq-parity.sh kson-cli/build/bin/linuxX64/releaseExecutable/kson.kexe
```

The common tests run on each target. `JacksonParityTest` compares strict parsing and round trips against Jackson on the JVM. `scripts/jq-parity-cases.txt` lists the jq comparison cases as `flags ::: filter ::: input`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
