import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin route planning and navigation logic. Independent of ARCore and rendering:
// it consumes venue-frame positions and LocalizationStatus from :core:domain only.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
