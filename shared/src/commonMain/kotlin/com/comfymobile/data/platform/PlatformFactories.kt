package com.comfymobile.data.platform

import app.cash.sqldelight.db.SqlDriver
import com.comfymobile.db.ComfyMobileDb
import com.russhwolf.settings.Settings

/**
 * Build the SQLDelight driver for [ComfyMobileDb] using the platform's
 * default database location. Android wires `AndroidSqliteDriver`
 * with the Application context; iOS wires `NativeSqliteDriver`.
 *
 * Implemented as a top-level `expect fun` so DI modules can call
 * `createSqlDriver(platformContext)` without knowing which platform
 * they're on.
 */
expect fun createSqlDriver(context: PlatformContext): SqlDriver

/**
 * Build the [Settings] instance backing
 * [com.comfymobile.data.persistence.SettingsServerHistoryStore].
 * Android uses `SharedPreferencesSettings`; iOS uses
 * `NSUserDefaultsSettings`.
 */
expect fun createSettings(context: PlatformContext): Settings
