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
        // Android 13: L2CAP channels (29) and the companion link's AssociationInfo API (33).
        minSdk = 33
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        // Release builds take the tag (v0.3.9 → 0.3.9); a local build is the work after the last release.
        versionName = System.getenv("CI_COMMIT_TAG")?.removePrefix("v") ?: "0.3.9-dev"
        ndk { abiFilters += listOf("arm64-v8a") }
        manifestPlaceholders["appLabel"] = "Air Gestures"
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
        getByName("debug") {
            // Debug builds are their own app (dev.kb896.<app>.dev, "(dev)", a "dev" tab on the
            // icon from src/debug/res), so one installs beside the release instead of over it.
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Air Gestures (dev)"
        }
        getByName("release") {
            isDebuggable = false
            // No R8: the JNI class dev.kb896.airgestures.Bridge must keep its names.
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // JVM tests of the pure parts (Mappings.kt); not AndroidX.
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only stubs on the JVM; the real one for the profile store's tests.
    testImplementation("org.json:json:20240303")
}
