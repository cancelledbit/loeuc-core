plugins {
    id("com.android.application")
}

// A complete LoEUC extension in one service: a tile row, an alert and a horn command. It is a
// real module of this build so it cannot quietly stop compiling against the contract.
android {
    namespace = "example.extension"
    compileSdk = 35

    defaultConfig {
        applicationId = "example.loeuc.range"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":loeuc-extension-api"))
}
