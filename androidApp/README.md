# `:androidApp` — Android shell

Thin wrapper around the `:shared` module. Single `MainActivity` hosting Compose root.

Phase 1.0 will add:

- `build.gradle.kts` with Android Gradle Plugin + Compose Compiler.
- `src/main/AndroidManifest.xml` declaring `MainActivity` and required permissions:
  - `INTERNET`
  - `ACCESS_NETWORK_STATE`
  - `ACCESS_WIFI_STATE`
- `src/main/kotlin/com/comfymobile/android/MainActivity.kt` calling the Compose root from `:shared`.
- Optional: launcher icon adaptive resources.

## Permissions and platform contracts

- **Wi-Fi-only enforcement**: implemented via `ConnectivityManager.getActiveNetwork()` + `NetworkCapabilities.hasTransport(TRANSPORT_WIFI)` in `:shared:platform/NetworkMonitor` (Android `actual`).
- **No background service** in v1 — connection is foreground-only (see ADR-0004).
- **No special media permissions** — image picker uses Photo Picker (API 33+) / SAF fallback; no `READ_MEDIA_IMAGES` runtime permission needed.
