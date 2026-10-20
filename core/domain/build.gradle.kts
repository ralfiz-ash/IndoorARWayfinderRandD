import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: public API, domain models and platform-independent logic.
// It must never depend on Android or ARCore so that it stays the reference
// contract for the future Swift/ARKit implementation.
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
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
