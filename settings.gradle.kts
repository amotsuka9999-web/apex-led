import java.util.Properties

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "apex-led"

// Pure Kotlin/JVM modules: no Android dependency, so they build and test without the Android SDK.
include(":pattern-engine")
include(":led-device")
include(":led-core")

// Android modules are only part of the build when an Android SDK is configured,
// so `./gradlew test` keeps working on a machine that has none.
val localProperties = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val androidSdkAvailable = localProperties.getProperty("sdk.dir") != null ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null

if (androidSdkAvailable) {
    include(":led-android")
    include(":app")
} else {
    logger.lifecycle("Android SDK not configured: skipping :led-android and :app")
}
