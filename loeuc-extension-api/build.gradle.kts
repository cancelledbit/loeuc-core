plugins {
    id("com.android.library")
    `maven-publish`
}

group = "pw.vasilevskiy"
version = "0.1.0"

// The contract between LoEUC and the extensions other people write for it: AIDL, the parcels
// that cross it, the manifest an extension declares and the rules the host applies to it.
// Android only - bound services across apps do not exist anywhere else, and this module must
// stay free of protocol bytes and brand names, which is why it does not depend on loeuc-core.
android {
    namespace = "pw.vasilevskiy.loeuc.extension"
    compileSdk = 35

    defaultConfig {
        // PackageManager.hasSigningCertificate, which the caller checks rely on, is API 28.
        minSdk = 28
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// What JitPack builds from the public repository (see jitpack.yml). The coordinates it serves
// are its own, com.github.cancelledbit.loeuc-core:loeuc-extension-api:<tag>.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "loeuc-extension-api"
                version = project.version.toString()
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
