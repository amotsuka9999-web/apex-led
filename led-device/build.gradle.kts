plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":pattern-engine"))
    api(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}
