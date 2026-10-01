plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

tasks.register("bumpVersion") {
    group = "versioning"
    description = "Bump the project, CLI, and README version (minor by default; -Ppart=major|patch)."

    doLast {
        val current = project.property("VERSION_NAME") as String
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(current)
            ?: error("VERSION_NAME must be major.minor.patch: $current")
        val (major, minor, patch) = match.destructured.toList().map(String::toInt)
        val next = when (val part = providers.gradleProperty("part").getOrElse("minor")) {
            "major" -> "${major + 1}.0.0"
            "minor" -> "$major.${minor + 1}.0"
            "patch" -> "$major.$minor.${patch + 1}"
            else -> error("Unknown version part: $part (use major, minor, or patch)")
        }
        // Each file must hold the current version, so a half-applied bump is caught instead of compounded.
        val replacements = mapOf(
            "gradle.properties" to listOf("VERSION_NAME=%s"),
            "kson-cli/src/commonMain/kotlin/com/fajarnuha/kson/cli/Cli.kt" to listOf("const val VERSION = \"%s\""),
            "README.md" to listOf(":%s\"", ":%s`"),
        )
        val updated = replacements.mapValues { (path, patterns) ->
            var text = file(path).readText()
            for (pattern in patterns) {
                val old = pattern.format(current)
                require(old in text) { "$path does not contain $old; versions must match before bumping" }
                text = text.replace(old, pattern.format(next))
            }
            text
        }
        updated.forEach { (path, text) -> file(path).writeText(text) }
        println("Bumped version $current -> $next")
    }
}
