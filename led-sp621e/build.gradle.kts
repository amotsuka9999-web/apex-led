plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    api(project(":core-model"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
