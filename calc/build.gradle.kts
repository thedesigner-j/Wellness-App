import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin calculation module: every number shown in the app is computed here.
// No Android dependencies, so all of it runs as plain JVM unit tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
