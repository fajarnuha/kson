plugins {
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        pluginManager.apply("com.vanniktech.maven.publish.base")
        val repositoryUrl = "https://github.com/fajarnuha/kson"
        val documentationJar = tasks.register<Jar>("publicationJavadocJar") {
            archiveClassifier.set("javadoc")
            from(rootProject.file("README.md"))
        }

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                artifact(documentationJar)
                pom {
                    developers {
                        developer {
                            id.set("fajarnuha")
                            name.set("Fajar Ulin Nuha")
                            url.set("https://fajarnuha.com")
                        }
                    }
                    scm {
                        url.set(repositoryUrl)
                        connection.set("scm:git:$repositoryUrl.git")
                        developerConnection.set("scm:git:ssh://git@github.com/fajarnuha/kson.git")
                    }
                }
            }
        }
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
        }
    }
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
