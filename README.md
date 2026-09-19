# kson

A small, dependency-free JSON toolkit for Kotlin Multiplatform:

- **`kson-core`**: a library with a JSON value model, a builder DSL, a strict RFC 8259 parser and writer, typed accessors, JSON Pointer, and JSON Schema inference.
- **`kson-cli`**: a Kotlin/Native command-line tool (`kson`) built on the library.

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

```
kson fmt data.json                 # pretty-print (stdin when no file or '-')
kson min data.json                 # minify
kson validate data.json            # exit 1 with file:line:column on error
kson get /users/0/name -r data.json
kson keys /users/0 data.json
kson type /users data.json
kson paths data.json               # every JSON Pointer
kson schema --title User data.json
kson merge base.json override.json # deep merge
kson build name=Fajar age:=42 address.city=Tangsel tags:='["a","b"]'
kson build -a one :=2 ':={"three":3}'
```

Output flags: `-c` compact, `-i N` indent width, `-t` tabs, `-s` sort keys, `-A` ASCII-only. Run `kson help` for the full list. Exit codes are 0 for success, 1 for invalid JSON or a missing pointer, and 2 for usage errors.

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
```

The common tests run on every target. The JVM-only `JacksonParityTest` checks that kson accepts, rejects and round-trips the same corpus as Jackson in strict mode.
