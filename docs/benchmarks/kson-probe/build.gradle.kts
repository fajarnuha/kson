plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.fajarnuha.kson:kson:0.6.0")
    ksp("com.fajarnuha.kson:kson-ksp:0.6.0")
}
