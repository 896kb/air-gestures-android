// Plain Android (no AndroidX); AGP 9 compiles the Kotlin sources itself.
// The Rust bridge is built into src/main/jniLibs by ../build-rust.sh.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.airgestures"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.airgestures"
        // L2CAP connection-oriented channels need API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Media that isn't ours to publish (the band's product video) lives in the
    // git-ignored local-assets/; the app works without it.
    sourceSets["main"].assets.directories += "local-assets"
}
