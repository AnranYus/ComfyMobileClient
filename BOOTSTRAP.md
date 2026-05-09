# Bootstrap

Phase 1.0 (T1.0) lands the KMP project skeleton + Compose Multiplatform Hello screen + Android APK build path. This document captures what's done and what comes next.

## What this commit adds

- ✅ Gradle wrapper (Gradle 8.10.2)
- ✅ `settings.gradle.kts` declaring `:shared` and `:androidApp`
- ✅ `gradle/libs.versions.toml` version catalog (Kotlin 2.0.21 / Compose Multiplatform 1.7.0 / AGP 8.7.3 / Ktor 3 / SQLDelight 2 / Coil 3 / Koin 4 / kotlinx-serialization)
- ✅ `:shared` KMP module with Android + iOS targets, common Compose UI (`App.kt`), iOS entry point (`MainViewController.kt`)
- ✅ `:androidApp` thin Android wrapper (`MainActivity` calls into `:shared:App()`)
- ✅ GitHub Actions CI: docs-and-structure, Android `assembleDebug` + APK artifact upload, iOS targets compile smoke
- ✅ `Greeting.kt` returns `ComfyMobileClient v0.0.1` for the Hello screen

## Local build (Android)

Requires Android SDK + JDK 17.

```bash
# From repo root:
./gradlew :androidApp:assembleDebug
# APK will be in androidApp/build/outputs/apk/debug/
```

The Android SDK location is read from `local.properties` (preferred) or `ANDROID_HOME` env var.

## CI-built APK

Every push to `main` and every PR runs `android-build` which uploads the debug APK as a workflow artifact. To grab a build:

1. Open the Actions tab → pick the latest run
2. Scroll to "Artifacts" → download `comfymobileclient-debug-apk`
3. Sideload to a real device (`adb install` or transfer to phone storage)

## What's NOT in this commit

These land in subsequent Phase 1 tasks:

- **iOS `iosApp.xcodeproj` + IPA** → T1.0b. Generated on a macOS GH Actions runner; PR'd back to `main`. Until then iOS targets compile but there's no app shell to launch on a simulator/device.
- **ComfyUI HTTP/WS client** → T1.1 (`shared/.../data/network/`)
- **Workflow data model + UI↔API conversion + PNG roundtrip** → T1.2 (`shared/.../domain/workflow/`)
- **Local job index + ghost-state recovery + Coil cache** → T1.3 (`shared/.../data/persistence/`)
- **Manual-IP connection UI** → T1.4 (`shared/.../presentation/connection/`)
- **Node descriptor v1 config + loader** → T1.5 (`shared/.../composeResources/files/node-descriptors/v1.json` + `data/descriptor/`)
- **QA harness** (UI↔API conversion unit tests, PNG roundtrip fixture, B/C ghost-state regression) → T1.6

## Versioning

`androidApp` `versionCode = 1`, `versionName = "0.0.1"` — bumped by future Phase 1 releases as features land.
