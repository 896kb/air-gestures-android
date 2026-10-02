// Plain Android (no AndroidX); AGP 9 compiles the Kotlin sources itself.
// The Rust bridge is built into src/main/jniLibs by ../build-rust.sh.
plugins {
    id("com.android.application")
}

// Release signing comes from the environment (see .gitlab-ci.yml). Without
// KEYSTORE_PATH the release build is left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.kb896.airgestures"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kb896.airgestures"
        // L2CAP connection-oriented channels need API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        // Release builds take the tag (v0.3.9 → 0.3.9); a local build is the work after the last release.
        versionName = System.getenv("CI_COMMIT_TAG")?.removePrefix("v") ?: "0.3.9-dev"
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
            // No R8: the JNI class dev.kb896.airgestures.Bridge must keep its names.
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}
