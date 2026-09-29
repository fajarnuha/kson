package bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.squareup.moshi.Moshi
import kotlinx.serialization.json.Json
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit
import com.fajarnuha.kson.Json as KsonJson

/** Decodes one user object (535 bytes) from a String. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class DecodeUser {
    private val text = Payloads.user

    @Benchmark fun kson() = bench.kson.UserJson.decode(text)
    @Benchmark fun kotlinx() = Codecs.kotlinx.decodeFromString(bench.kotlinx.User.serializer(), text)
    @Benchmark fun moshi() = Codecs.moshiUser.fromJson(text)
    @Benchmark fun gson() = Codecs.gson.fromJson(text, bench.gson.User::class.java)
    @Benchmark fun jackson() = Codecs.jackson.readValue(text, bench.jackson.User::class.java)
}

/** Decodes a page of [size] users from a String (20 users = 9.5 KB, 1000 users = 489 KB). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class DecodePage {
    @Param("20", "1000")
    @JvmField
    var size: Int = 0

    private lateinit var text: String

    @Setup
    fun setup() {
        text = Payloads.page(size)
    }

    @Benchmark fun kson() = bench.kson.UserPageJson.decode(text)
    @Benchmark fun kotlinx() = Codecs.kotlinx.decodeFromString(bench.kotlinx.UserPage.serializer(), text)
    @Benchmark fun moshi() = Codecs.moshiPage.fromJson(text)
    @Benchmark fun gson() = Codecs.gson.fromJson(text, bench.gson.UserPage::class.java)
    @Benchmark fun jackson() = Codecs.jackson.readValue(text, bench.jackson.UserPage::class.java)
}

/** Encodes a decoded page of [size] users to a String. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class EncodePage {
    @Param("20", "1000")
    @JvmField
    var size: Int = 0

    private lateinit var kson: bench.kson.UserPage
    private lateinit var kotlinx: bench.kotlinx.UserPage
    private lateinit var moshi: bench.moshi.UserPage
    private lateinit var gson: bench.gson.UserPage
    private lateinit var jackson: bench.jackson.UserPage

    @Setup
    fun setup() {
        val text = Payloads.page(size)
        kson = bench.kson.UserPageJson.decode(text)
        kotlinx = Codecs.kotlinx.decodeFromString(bench.kotlinx.UserPage.serializer(), text)
        moshi = Codecs.moshiPage.fromJson(text)!!
        gson = Codecs.gson.fromJson(text, bench.gson.UserPage::class.java)
        jackson = Codecs.jackson.readValue(text, bench.jackson.UserPage::class.java)
    }

    @Benchmark fun kson() = bench.kson.UserPageJson.encode(kson).toJson()
    @Benchmark fun kotlinx() = Codecs.kotlinx.encodeToString(bench.kotlinx.UserPage.serializer(), kotlinx)
    @Benchmark fun moshi() = Codecs.moshiPage.toJson(moshi)
    @Benchmark fun gson() = Codecs.gson.toJson(gson)
    @Benchmark fun jackson() = Codecs.jackson.writeValueAsString(jackson)
}

/** Parses a page of [size] users into each library's untyped tree (no data binding). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ParseTree {
    @Param("20", "1000")
    @JvmField
    var size: Int = 0

    private lateinit var text: String

    @Setup
    fun setup() {
        text = Payloads.page(size)
    }

    @Benchmark fun kson() = KsonJson.parse(text)
    @Benchmark fun kotlinx() = Codecs.kotlinx.parseToJsonElement(text)
    @Benchmark fun moshi() = Codecs.moshiTree.fromJson(text)
    @Benchmark fun gson() = JsonParser.parseString(text)
    @Benchmark fun jackson() = Codecs.jackson.readTree(text)
}

/**
 * Time for the very first decode in a fresh JVM: class loading, codec/adapter creation, and interpreted execution.
 * Each fork runs exactly one decode. The shared [Codecs] object is deliberately not touched here, so no other
 * library gets initialized first.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(20)
open class ColdStart {
    private lateinit var text: String

    @Setup
    fun setup() {
        text = Payloads.user
    }

    @Benchmark fun kson() = bench.kson.UserJson.decode(text)
    @Benchmark fun kotlinx() = Json.decodeFromString(bench.kotlinx.User.serializer(), text)
    @Benchmark fun moshi() = Moshi.Builder().build().adapter(bench.moshi.User::class.java).fromJson(text)
    @Benchmark fun gson() = Gson().fromJson(text, bench.gson.User::class.java)
    @Benchmark fun jackson() = jacksonObjectMapper().readValue(text, bench.jackson.User::class.java)
}
