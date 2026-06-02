plugins {
    alias(libs.plugins.androidApplication)
    kotlin("android")
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.comfymobile.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.comfymobile.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.1.1"
    }

    buildFeatures {
        compose = true
    }

    // Pin the debug signing identity to a repo-checked-in keystore so every
    // CI runner produces APKs with the same SHA-256 certificate fingerprint.
    // Without this, AGP auto-generates a per-machine debug.keystore at
    // ~/.android/debug.keystore on first build, which is the reason
    // v0.1.0 and v0.1.1 had different cert SHA-256s and Android refused
    // the in-place upgrade (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    //
    // The keystore + password are intentionally well-known (Android Debug
    // defaults — same identity AGP would have generated locally). Debug-
    // signed APKs cannot be uploaded to Play Store and have no security
    // value beyond identifying the build channel; release signing will
    // ship as its own ADR + isolated keystore at v0.3+ (not in this PR).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-fixed.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
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
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ComfyMobileApplication.onCreate calls startKoin and resolves
    // singletons by type, so the app target needs koin-core directly
    // (the :shared module declares it as `implementation`, which does
    // not transit to dependents in KMP).
    implementation(libs.koin.core)

    // Compose BOM equivalents are pulled transitively via :shared (Compose Multiplatform);
    // we only add Android-specific entries here.
}
