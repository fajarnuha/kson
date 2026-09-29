pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kson-root"
include(":kson")
include(":kson-cli")
include(":kson-ksp")
include(":kson-ktor")
include(":kson-retrofit")
include(":kson-playground")
