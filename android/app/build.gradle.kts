plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.owlwhisp.mobile"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    defaultConfig {
        applicationId = "ai.owlwhisp.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    signingConfigs {
        if (System.getenv("OWLWHISP_KEYSTORE") != null) create("release") {
            storeFile = file(System.getenv("OWLWHISP_KEYSTORE"))
            storePassword = System.getenv("OWLWHISP_STORE_PASSWORD")
            keyAlias = System.getenv("OWLWHISP_KEY_ALIAS")
            keyPassword = System.getenv("OWLWHISP_KEY_PASSWORD")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint { abortOnError = true }
}
dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
}
