pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kson-serialization-benchmarks"

// Build kson and kson-ksp from this checkout instead of a published release.
includeBuild("../..")

// Scratch module used by scripts/kson-type-probe.sh to check which property types kson-ksp accepts.
include(":kson-probe")
