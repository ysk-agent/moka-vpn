plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.moriya.vpn"
    // Template parity with apps-updater: compileSdk 35 / targetSdk 34.
    // Upstream tailscale-android now uses androidApiLevel 36 (see
    // DESIGN.md §8) — the gomobile AAR is built with -androidapi 26, so
    // consuming it here is expected to work, but the first CI build is
    // the discriminator; bump these to 36 rather than forcing the AAR.
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.moriya.vpn"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Pinned signing key, same pattern as apps-updater / yt-player /
    // phone-node-waker: CI decodes the keystore from the
    // SIGNING_KEYSTORE_B64 repo secret and points SIGNING_KEYSTORE at
    // it, so every build carries the same certificate and can
    // overwrite-install over previous builds.
    signingConfigs {
        create("pinned") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASS")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("pinned")
            }
            // Fixed output name, per the APK handover standard.
            // (CI also copies/renames explicitly; this makes local
            // artifact names match.)
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // libtailscale, built in CI from unmodified upstream source and
    // dropped at app/libs/libtailscale.aar (see .github/workflows/
    // build.yml and DESIGN.md §6). files() (not a flatDir repository)
    // on purpose: when the AAR is absent — every build until the AAR
    // step is wired, and the x86_64 emulator launch test — the
    // dependency is simply empty and the app compiles and runs against
    // the Layer 3 stub, which reports BackendUnavailable. Only the
    // dev.moriya.vpn.backend.libtailscale package may ever reference
    // types from this AAR.
    implementation(files("libs/libtailscale.aar"))

    // No other dependencies in v0.1: framework APIs only, mirroring
    // apps-updater. The adapter's AppContext encrypted storage is also
    // dependency-free by decision (Moriya, 2026-11-14; DESIGN.md §8):
    // EncryptedPrefStore implements it over Android Keystore AES-GCM
    // inside Layer 3, so no Jetpack Security dependency exists.
}
