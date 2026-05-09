package com.comfymobile.data.platform

/**
 * Holder for platform-supplied dependencies that the DI module
 * needs access to. On Android this carries the Application
 * `Context`; on iOS it's empty (Foundation APIs are accessible
 * without a context).
 *
 * Instantiated once at app startup (`MainActivity.onCreate` /
 * `iOSApp.swift`) and passed into the Koin module.
 */
expect class PlatformContext
