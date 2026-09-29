package bench

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.gson.Gson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.serialization.json.Json

/**
 * The codec instances every measurement shares. Each library runs in its default configuration, which is what
 * a new user gets: `Json` (kotlinx), `Moshi.Builder().build()` with codegen adapters, `Gson()`, and
 * `jacksonObjectMapper()`. kson has no configuration; its codecs are the KSP-generated `*Json` objects.
 */
object Codecs {
    val kotlinx: Json = Json

    val moshi: Moshi = Moshi.Builder().build()
    val moshiUser: JsonAdapter<bench.moshi.User> = moshi.adapter(bench.moshi.User::class.java)
    val moshiPage: JsonAdapter<bench.moshi.UserPage> = moshi.adapter(bench.moshi.UserPage::class.java)
    val moshiTree: JsonAdapter<Any> = moshi.adapter(Any::class.java)

    val gson: Gson = Gson()

    val jackson: ObjectMapper = jacksonObjectMapper()
}
