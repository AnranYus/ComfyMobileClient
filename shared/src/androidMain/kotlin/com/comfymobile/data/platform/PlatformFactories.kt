package com.comfymobile.data.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.comfymobile.db.ComfyMobileDb
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

actual fun createSqlDriver(context: PlatformContext): SqlDriver =
    AndroidSqliteDriver(
        schema = ComfyMobileDb.Schema,
        context = context.androidContext,
        name = "comfy.db",
    )

actual fun createSettings(context: PlatformContext): Settings {
    val prefs = context.androidContext.getSharedPreferences(
        "comfy.prefs",
        Context.MODE_PRIVATE,
    )
    return SharedPreferencesSettings(prefs)
}

actual fun nowEpochMs(): Long = System.currentTimeMillis()
