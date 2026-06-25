pluginManagement {
    val quarkusPluginVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.quarkus") version quarkusPluginVersion
        id("io.quarkus.extension") version quarkusPluginVersion
    }
}

rootProject.name = "demo-extension"

include(":greeting-extension:runtime")
include(":greeting-extension:deployment")
include(":app")
