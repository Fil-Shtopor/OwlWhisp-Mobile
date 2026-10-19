plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.owlwhisp.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.owlwhisp.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main").assets.srcDir("../../shared")
        getByName("debug").assets.srcDir("../../tests/fixtures")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint { abortOnError = true }
}
dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
}
