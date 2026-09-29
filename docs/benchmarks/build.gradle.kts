import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.10"
    id("me.champeau.jmh") version "0.7.3"
}

val versions = mapOf(
    "kson" to "0.6.0", // substituted with the checkout by includeBuild("../..")
    "kotlinx" to "1.11.0",
    "moshi" to "1.15.2",
    "gson" to "2.14.0",
    "jackson" to "2.22.3",
)

dependencies {
    implementation("com.fajarnuha.kson:kson:${versions["kson"]}")
    ksp("com.fajarnuha.kson:kson-ksp:${versions["kson"]}")
    implementation("com.fajarnuha.kson:kson-ktor:${versions["kson"]}") // only for KsonChecks

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${versions["kotlinx"]}")

    implementation("com.squareup.moshi:moshi:${versions["moshi"]}")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:${versions["moshi"]}")

    implementation("com.google.code.gson:gson:${versions["gson"]}")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${versions["jackson"]}")

    implementation("org.openjdk.jol:jol-core:0.17")
}

jmh {
    // Overridable from the command line, e.g. -Pjmh.includes=Decode
    includes.set(listOf(providers.gradleProperty("jmh.includes").getOrElse(".*")))
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.projectDirectory.file("results/jmh-raw.json"))
    humanOutputFile.set(layout.projectDirectory.file("results/jmh-human.txt"))
    jvmArgs.set(listOf("-Xms1g", "-Xmx1g"))
}

val resultsDir = layout.projectDirectory.dir("results")

fun mainRunner(name: String, mainClass: String, description: String) =
    tasks.register<JavaExec>(name) {
        group = "comparison"
        this.description = description
        classpath = sourceSets.main.get().runtimeClasspath
        this.mainClass.set(mainClass)
        jvmArgs("-Xms1g", "-Xmx1g", "-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
        args(resultsDir.asFile.absolutePath)
    }

mainRunner("behavior", "bench.BehaviorMatrixKt", "Runs the decode/encode edge cases against every library")
mainRunner("ksonChecks", "bench.KsonChecksKt", "Checks kson-specific behavior: equality across roots and Ktor error wrapping")
mainRunner("retained","bench.RetainedHeapKt", "Measures the retained heap of decoded objects with JOL")

// ---------------------------------------------------------------------------------------------
// Dependency footprint: the runtime jars each library adds to an app.
// ---------------------------------------------------------------------------------------------

val footprintLibraries = mapOf(
    "kson" to listOf("com.fajarnuha.kson:kson:${versions["kson"]}"),
    "kotlinx.serialization" to listOf("org.jetbrains.kotlinx:kotlinx-serialization-json:${versions["kotlinx"]}"),
    "moshi" to listOf("com.squareup.moshi:moshi:${versions["moshi"]}"),
    "gson" to listOf("com.google.code.gson:gson:${versions["gson"]}"),
    "jackson (+kotlin module)" to listOf("com.fasterxml.jackson.module:jackson-module-kotlin:${versions["jackson"]}"),
)

val footprintConfigurations = footprintLibraries.mapValues { (library, coordinates) ->
    configurations.create("footprint" + library.filter(Char::isLetter).replaceFirstChar(Char::uppercase)) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
        coordinates.forEach { dependencies.add(project.dependencies.create(it)) }
    }
}

tasks.register("footprint") {
    group = "comparison"
    description = "Lists the runtime jars each library pulls in, and the bytecode of the benchmark models"
    dependsOn(tasks.compileKotlin)
    val classesDir = layout.buildDirectory.dir("classes/kotlin/main/bench")
    val out = resultsDir.file("footprint.md")
    val resolved = footprintConfigurations.mapValues { (_, configuration) ->
        configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { it.id.componentIdentifier.displayName to it.file }
        }
    }
    doLast {
        val shared = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
        fun isShared(id: String) = shared.any { id.startsWith(it) }
        fun kb(bytes: Long) = "%,.1f KB".format(bytes / 1024.0)
        val text = buildString {
            appendLine("## Runtime dependencies")
            appendLine()
            appendLine("Jars resolved for each library's runtime classpath. `kotlin-stdlib` and `org.jetbrains:annotations` are")
            appendLine("listed but left out of the total, because every Kotlin app already has them.")
            appendLine()
            appendLine("| Library | Jars (excluding stdlib) | Total size |")
            appendLine("|---|---|---:|")
            resolved.forEach { (library, artifacts) ->
                val jars = artifacts.get()
                val own = jars.filterNot { isShared(it.first) }
                val list = own.joinToString("<br>") { "`${it.first}` (${kb(it.second.length())})" }
                appendLine("| $library | $list | ${kb(own.sumOf { it.second.length() })} |")
            }
            appendLine()
            appendLine("## Model bytecode")
            appendLine()
            appendLine("Compiled classes for the same benchmark model (`User`, `UserPage`, `Address`, `Geo`, `Friend`, `Role`),")
            appendLine("including everything the library's code generator emitted for it.")
            appendLine()
            appendLine("| Library | Classes | Bytes | Generated classes |")
            appendLine("|---|---:|---:|---|")
            classesDir.get().asFile.listFiles().orEmpty().sortedBy { it.name }.filter { it.isDirectory }.forEach { dir ->
                val classes = dir.walkTopDown().filter { it.extension == "class" }.toList()
                val generated = classes.map { it.nameWithoutExtension }
                    .filter { "Json" in it || "serializer" in it || "Adapter" in it || "Builder" in it || "Impl" in it }
                appendLine("| ${dir.name} | ${classes.size} | ${"%,d".format(classes.sumOf { it.length() })} | ${generated.size} |")
            }
        }
        out.asFile.writeText(text)
        println(text)
    }
}
