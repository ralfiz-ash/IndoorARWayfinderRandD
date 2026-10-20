// ARCore adapter: the only module that sees ARCore types. Everything it exposes uses domain types.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.indoorar.wayfinder.arcore"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:domain"))
    api(libs.arcore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
