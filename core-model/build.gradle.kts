plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.kotlin.test)
}
