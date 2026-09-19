plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val nativeTargets = listOf(
        macosArm64(),
        macosX64(),
        linuxX64(),
        linuxArm64(),
        mingwX64(),
    )

    nativeTargets.forEach { target ->
        target.binaries.executable {
            baseName = "kson"
            entryPoint = "com.fajarnuha.kson.cli.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kson-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
