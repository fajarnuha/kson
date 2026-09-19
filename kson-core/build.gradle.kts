import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Desktop / server native targets
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Apple mobile targets
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            // Reference implementation used only to prove parser/writer parity.
            implementation(libs.jackson.databind)
        }
    }
}

publishing {
    repositories {
        // Private GitHub Packages registry. Needs GITHUB_ACTOR + GITHUB_TOKEN (a token with write:packages).
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/fajarnuha/kson")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kson")
            description.set("A tiny, dependency-free JSON value model, DSL builder, parser, writer and JSON Schema inference for Kotlin Multiplatform.")
            url.set("https://github.com/fajarnuha/kson")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
        }
    }
}
