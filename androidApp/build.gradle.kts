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
