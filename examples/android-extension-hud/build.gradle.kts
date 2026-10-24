plugins {
    id("com.android.application")
}

// An extension that bridges LoEUC to hardware of your own: telemetry goes out to a BLE
// peripheral (an ESP32 sketch is in firmware/), button presses come back as wheel commands.
android {
    namespace = "example.hud"
    compileSdk = 35

    defaultConfig {
        applicationId = "example.loeuc.hud"
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
    testImplementation("junit:junit:4.13.2")
}
