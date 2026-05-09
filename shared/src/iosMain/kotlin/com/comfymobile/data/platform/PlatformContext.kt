package com.comfymobile.data.platform

/**
 * iOS specialisation: empty value. Foundation APIs (NSUserDefaults,
 * NWPathMonitor, …) are accessible without a context object, so the
 * DI module on iOS doesn't need to thread platform state through.
 */
actual class PlatformContext
