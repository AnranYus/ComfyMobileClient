package com.comfymobile.data.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.comfymobile.db.ComfyMobileDb
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

actual fun createSqlDriver(context: PlatformContext): SqlDriver =
    NativeSqliteDriver(
        schema = ComfyMobileDb.Schema,
        name = "comfy.db",
    )

actual fun createSettings(context: PlatformContext): Settings =
    NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)

actual fun nowEpochMs(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
