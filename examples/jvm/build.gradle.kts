plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":loeuc-core"))
    implementation(project(":loeuc-coroutines"))
}

application {
    mainClass.set("example.MainKt")
}
