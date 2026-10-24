pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "loeuc-core"
include(":loeuc-core", ":loeuc-coroutines", ":loeuc-extension-api")

// A runnable example, so nothing in examples/ can quietly stop compiling.
include(":examples:jvm")
include(":examples:android-extension")
