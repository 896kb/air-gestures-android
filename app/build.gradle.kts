// Plain Android (no AndroidX); AGP 9 compiles the Kotlin sources itself.
// The Rust bridge is built into src/main/jniLibs by ../build-rust.sh.
plugins {
    id("com.android.application")
}

// Release signing comes from the environment (see .gitlab-ci.yml). Without
// KEYSTORE_PATH the release build is left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.airgestures"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.airgestures"
        // L2CAP connection-oriented channels need API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.3.6"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            // No R8: the JNI class dev.airgestures.Bridge must keep its names.
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}
