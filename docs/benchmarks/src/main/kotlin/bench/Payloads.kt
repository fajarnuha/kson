package bench

import kotlin.random.Random

/**
 * Generates the benchmark JSON with a plain StringBuilder, so that building the input loads no serialization
 * library classes (which would otherwise warm up one library before a cold-start measurement).
 */
object Payloads {
    /** One user object, 535 bytes as UTF-8. */
    val user: String by lazy { buildString { user(Random(42), 1) } }

    /** A page of [size] users. */
    fun page(size: Int): String = buildString {
        val random = Random(42)
        append("{\"page\":1,\"total\":").append(size).append(",\"users\":[")
        for (i in 1..size) {
            if (i > 1) append(',')
            user(random, i.toLong())
        }
        append("]}")
    }

    private val names = listOf("Leanne Graham", "Ervin Howell", "José Álvarez", "Zoë Müller", "李雷", "Priya Ramanathan")
    private val cities = listOf("Gwenborough", "Wisokyburgh", "São Paulo", "Kraków", "東京", "Bandung")
    private val tags = listOf("admin", "beta", "kotlin", "android", "ios", "backend", "early-adopter")
    private val roles = listOf("ADMIN", "EDITOR", "VIEWER")

    private fun StringBuilder.user(random: Random, id: Long) {
        val name = names[random.nextInt(names.size)]
        append('{')
        field("id").append(id).append(',')
        field("name").string(name).append(',')
        field("username").string("user_$id").append(',')
        field("email").string("user$id@example.com").append(',')
        field("active").append(random.nextBoolean()).append(',')
        field("score").append(random.nextDouble() * 1000).append(',')
        field("role").string(roles[random.nextInt(roles.size)]).append(',')
        field("nickname")
        if (random.nextBoolean()) string("nick$id") else append("null")
        append(',')
        field("bio").string("Line one\nSays \"hello\" ✓ — ${name.reversed()}").append(',')
        field("address").append('{')
        field("street").string("${random.nextInt(1, 9999)} Kulas Light").append(',')
        field("city").string(cities[random.nextInt(cities.size)]).append(',')
        field("zipcode").string("%05d-%04d".format(random.nextInt(100000), random.nextInt(10000))).append(',')
        field("geo").append('{')
        field("lat").append(random.nextDouble(-90.0, 90.0)).append(',')
        field("lng").append(random.nextDouble(-180.0, 180.0))
        append("}},")
        field("tags").append('[')
        repeat(random.nextInt(0, 6)) { i ->
            if (i > 0) append(',')
            string(tags[random.nextInt(tags.size)])
        }
        append("],")
        field("friends").append('[')
        repeat(random.nextInt(0, 6)) { i ->
            if (i > 0) append(',')
            append('{')
            field("id").append(random.nextLong(1, 1_000_000)).append(',')
            field("name").string(names[random.nextInt(names.size)])
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.field(name: String): StringBuilder = append('"').append(name).append("\":")

    private fun StringBuilder.string(value: String): StringBuilder {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        return append('"')
    }
}
