plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("ComfyMobileDb") {
            packageName.set("com.comfymobile.db")
            // ADR-0006 §5 wanted `verifyMigrations.set(true)` here so
            // sqldelight would fail-fast on any future `.sq` ↔ `.sqm`
            // divergence. That is on hold for one PR: enabling it
            // surfaces a pre-existing Phase 2 bootstrap gap — the
            // existing 1.sqm/2.sqm/3.sqm are pure `ALTER TABLE`
            // statements that assume prior schemas exist, so a
            // verifyMigrations run from an empty DB cannot reach v3.
            // The fix is to commit baseline `1.db` / `2.db` / `3.db`
            // schema dumps alongside the `.sqm` files — that work is a
            // separate task tracked at the channel level (see thread
            // for ADR-0006 alignment). For T3.1's purposes the
            // Migration4Test in `androidUnitTest` is the explicit proof
            // that 3 → 4 works correctly and that this multi-statement
            // `.sqm` executes under the bundled SQLite driver.
            //
            // schemaOutputDirectory stays on so the golden `databases/
            // 4.db` schema dump still lands in the PR — it will be
            // consumed by `verifyMigrations` once the baselines are
            // bootstrapped.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.websockets)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)

            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            // Tests construct an HttpClient with ContentNegotiation +
            // kotlinx-JSON for MockEngine, so make those artifacts
            // available on the test classpath explicitly. (commonMain
            // declares them as `implementation`, which does NOT make
            // them visible to commonTest in KMP.)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // MapSettings (in-memory Settings impl) for
            // SettingsServerHistoryStoreTest. Lives in the
            // -test artifact, separate from the main
            // multiplatform-settings dep.
            implementation(libs.multiplatform.settings.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        val androidUnitTest by getting {
            dependencies {
                // Regression coverage for the AndroidLifecycleMonitor
                // dispatcher contract (P0 launch-crash fix). androidx
                // Lifecycle types are pulled in via the main dep
                // (lifecycle-process transitively brings lifecycle-common)
                // so we just need kotlin test + coroutines test here.
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // ADR-0006 §6a: JVM-only in-memory SQLite driver for
                // Migration4Test + the newInMemoryComfyMobileDb() helper.
                // Lives in androidUnitTest (not commonTest) so it doesn't
                // bleed into a future iosTest compile path.
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

// Pin the generated `Res` class to a stable package so production code
// has a predictable import. Compose Multiplatform infers a default if
// this is omitted, but we'd rather not depend on the inference.
compose.resources {
    publicResClass = false
    packageOfResClass = "com.comfymobile.resources"
    generateResClass = auto
}

android {
    namespace = "com.comfymobile.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
