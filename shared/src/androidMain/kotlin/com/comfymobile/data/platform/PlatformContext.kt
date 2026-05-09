package com.comfymobile.data.platform

import android.content.Context

/**
 * Android specialisation: wraps the Application [Context] so the
 * DI module can build `AndroidNetworkMonitor`, `AndroidSqliteDriver`,
 * `SharedPreferences`-backed `Settings`, etc.
 */
actual class PlatformContext(val androidContext: Context)
