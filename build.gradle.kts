plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

tasks.register("bumpVersion") {
    group = "versioning"
    description = "Bump the project and CLI version (minor by default; -Ppart=major|patch)."

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
        val propertiesFile = file("gradle.properties")
        val cliFile = file("kson-cli/src/commonMain/kotlin/com/fajarnuha/kson/cli/Cli.kt")
        val propertiesText = propertiesFile.readText()
        val cliText = cliFile.readText()
        val propertiesVersion = "VERSION_NAME=$current"
        val cliVersion = "const val VERSION = \"$current\""
        require(propertiesText.contains(propertiesVersion) && cliText.contains(cliVersion)) {
            "Project and CLI versions must match before bumping"
        }
        propertiesFile.writeText(propertiesText.replace(propertiesVersion, "VERSION_NAME=$next"))
        cliFile.writeText(cliText.replace(cliVersion, "const val VERSION = \"$next\""))
        println("Bumped version $current -> $next")
    }
}
