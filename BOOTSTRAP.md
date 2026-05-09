# Bootstrap — first Phase 1 commit

This file lists what's needed to make the repo **runnable** for the first time. Phase 0 closed with this skeleton + design docs; Phase 1 starts by filling in the Gradle build.

## What to add (Phase 1.0 task)

1. **Gradle wrapper** — generate via `gradle wrapper --gradle-version 8.x` (locally, with a Gradle 8.x install on PATH).
2. **Top-level build files**:
   - `settings.gradle.kts` — declares `:shared`, `:androidApp` (and `:iosApp` if used as Gradle module).
   - `build.gradle.kts` — root configuration.
   - `gradle/libs.versions.toml` — version catalog (skeleton in `docs/architecture/T0.2-kmp-skeleton-and-rendering.md` §M1).
3. **`shared/` module**:
   - `shared/build.gradle.kts` declaring `kotlin { androidTarget(); iosX64(); iosArm64(); iosSimulatorArm64() }`.
   - Stub `commonMain/kotlin/com/comfymobile/Hello.kt` returning a string.
4. **`androidApp/` module**:
   - `androidApp/build.gradle.kts` with Compose plugin.
   - `androidApp/src/main/AndroidManifest.xml` with single `MainActivity`.
   - `androidApp/src/main/kotlin/com/comfymobile/android/MainActivity.kt` calling Compose root.
5. **`iosApp/`**:
   - Either a Compose Multiplatform `iosApp.xcodeproj` (generated via Xcode + KMP plugin), or a SwiftUI shim that consumes `:shared:framework`.
   - This step requires running on macOS to produce the `xcodeproj`; can be done by any team member with macOS in CI.
6. **CI smoke** — see `.github/workflows/ci.yml` placeholder.

## Recommended: regenerate from JetBrains KMP Wizard

The simplest path is to run the [Kotlin Multiplatform Wizard](https://kmp.jetbrains.com/) with:
- Targets: Android, iOS
- UI: Compose Multiplatform (shared UI)
- Module name: `shared`
- Application id: `com.comfymobile`

…then merge the generated structure into this repo, preserving `docs/`, `README.md`, `.gitignore`, and `.github/`.

## Why not include a generated KMP wizard output now?

The ComfyMobileClient repo was created fresh per @nothing's preference (the prior `ComfyMobile` repo had a wizard template but was set aside to "新起项目"). Generating the wizard requires interactive web flow + macOS for the xcodeproj. Phase 0 closes by capturing **what** to scaffold; Phase 1.0 will do the actual scaffolding and verify builds.
