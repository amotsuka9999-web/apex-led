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

// Módulos Kotlin/JVM puros (sem Android): lógica testável em qualquer máquina.
include(":core-model")
include(":wheel-protocols")
include(":led-sp621e")
include(":safety-engine")
// Módulos Android.
include(":data-source")
include(":app")
