buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // AGP 9's built-in Kotlin bundles KGP 2.2.10; pin the newer Kotlin from gradle/libs.versions.toml
        // so built-in Kotlin and the Compose/serialization compiler plugins all use it.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}