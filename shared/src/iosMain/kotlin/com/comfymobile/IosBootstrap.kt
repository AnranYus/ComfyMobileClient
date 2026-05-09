package com.comfymobile

import com.comfymobile.data.di.appModule
import com.comfymobile.data.di.bootSharedRuntime
import com.comfymobile.data.di.iosPlatformModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin

/**
 * iOS process-lifetime bootstrap.
 *
 * Called from `iOSApp.swift` once at app launch — equivalent to
 * Android's [com.comfymobile.android.ComfyMobileApplication]
 * `onCreate`. Per @Lily PR #18 thread (`62385887`), the state
 * machine + bootstrap must follow the *process* lifetime, not the
 * SwiftUI scene lifetime.
 *
 * Idempotent: the cached [koinApp] guards against double-init if
 * SwiftUI re-runs the `App.init` somehow under hot-reload. We hold
 * a direct reference to the [KoinApplication] returned by
 * [startKoin] rather than going through `GlobalContext` /
 * `KoinPlatformTools` so the iOS K/N target compiles (per @Lily
 * PR #19 review comment `4413957569` blocker 1).
 *
 * Process-startup work (state machine start, bootstrap start, async
 * descriptor-registry load) is delegated to the shared
 * `bootSharedRuntime(koin)` helper so this iOS bootstrap never
 * reaches into shared `internal` qualifiers.
 *
 * Exposed under the Swift name `IosBootstrapKt.bootKoinIos()`.
 */
private var koinApp: KoinApplication? = null

@Suppress("FunctionName", "unused")
fun bootKoinIos() {
    if (koinApp != null) return

    val app = startKoin {
        modules(
            appModule(),
            iosPlatformModule(),
        )
    }
    koinApp = app

    bootSharedRuntime(app.koin)
}
