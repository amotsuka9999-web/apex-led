pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "apex-led"

// Pure Kotlin/JVM modules: no Android dependency, so they build and test without the Android SDK.
include(":pattern-engine")
include(":led-device")
include(":led-core")
