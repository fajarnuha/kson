plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}
