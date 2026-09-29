package probe

import com.fajarnuha.kson.Kson
import com.squareup.moshi.JsonClass
import kotlinx.serialization.Serializable

// The same small model in each library's idiom. `nickname` is optional and `tags` has a default.

enum class ProbeRole { ADMIN, VIEWER }

@Kson
interface KsonProbe {
    val id: Int
    val name: String
    val score: Double
    val role: ProbeRole
    val nickname: String?
    val tags: List<String> get() = emptyList()
    val inner: Inner

    interface Inner {
        val value: Long
    }
}

@Serializable
data class KotlinxProbe(
    val id: Int,
    val name: String,
    val score: Double,
    val role: ProbeRole,
    val nickname: String? = null,
    val tags: List<String> = emptyList(),
    val inner: KotlinxInner,
)

@Serializable
data class KotlinxInner(val value: Long)

@JsonClass(generateAdapter = true)
data class MoshiProbe(
    val id: Int,
    val name: String,
    val score: Double,
    val role: ProbeRole,
    val nickname: String? = null,
    val tags: List<String> = emptyList(),
    val inner: MoshiInner,
)

@JsonClass(generateAdapter = true)
data class MoshiInner(val value: Long)

data class GsonProbe(
    val id: Int,
    val name: String,
    val score: Double,
    val role: ProbeRole,
    val nickname: String? = null,
    val tags: List<String> = emptyList(),
    val inner: GsonInner,
)

data class GsonInner(val value: Long)

data class JacksonProbe(
    val id: Int,
    val name: String,
    val score: Double,
    val role: ProbeRole,
    val nickname: String? = null,
    val tags: List<String> = emptyList(),
    val inner: JacksonInner,
)

data class JacksonInner(val value: Long)
