plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    jacoco
}

group = "pw.vasilevskiy"
version = "0.1.0"

kotlin {
    android {
        namespace = "pw.vasilevskiy.loeuc.shared.api"
        compileSdk = 35
        minSdk = 24
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The library's own tests assert the exact bytes of the write commands, so they opt in
        // wholesale. Library sources do not: each place that builds a write command marks
        // itself with @OptIn, which is what keeps the marker meaningful for a consumer.
        all {
            if (name.endsWith("Test")) {
                languageSettings.optIn("pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi")
            }
        }
    }
}

// Coverage over commonMain, measured through the jvm target because it runs the whole
// commonTest suite without a device.
tasks.register<JacocoReport>("jvmCoverageReport") {
    dependsOn("jvmTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/jvmTest.exec"))
    sourceDirectories.setFrom(files("src/commonMain/kotlin"))
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("classes/kotlin/jvm/main")))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
