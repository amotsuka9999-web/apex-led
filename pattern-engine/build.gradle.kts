plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately dependency-free: the pattern engine is a pure function library.
dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}
