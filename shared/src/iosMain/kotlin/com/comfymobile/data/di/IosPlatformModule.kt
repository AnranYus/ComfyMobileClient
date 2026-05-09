package com.comfymobile.data.di

import com.comfymobile.data.connection.IosLifecycleMonitor
import com.comfymobile.data.connection.IosNetworkMonitor
import com.comfymobile.data.platform.PlatformContext
import com.comfymobile.domain.connection.LifecycleMonitor
import com.comfymobile.domain.connection.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS-side Koin platform module.
 *
 * Bound here:
 *  - [PlatformContext] → empty actual; iOS Foundation APIs are
 *    accessible without a context object, so this is just a marker
 *    that satisfies commonMain DI calls (`createSqlDriver`,
 *    `createSettings`).
 *  - [NetworkMonitor] → [IosNetworkMonitor] backed by
 *    `NWPathMonitor` registered against the [APP_SCOPE] coroutine
 *    scope.
 *  - [LifecycleMonitor] → [IosLifecycleMonitor] backed by
 *    `UIApplicationDidBecomeActive` /
 *    `UIApplicationDidEnterBackground` notifications.
 *
 * Usage from the Swift / Kotlin/Native bootstrap (`MainViewController`
 * or the SwiftUI host) — call once at app cold-start:
 *
 *   startKoin {
 *       modules(
 *           appModule(),
 *           iosPlatformModule(),
 *       )
 *   }
 */
fun iosPlatformModule(): Module = module {

    single<PlatformContext> { PlatformContext() }

    single<NetworkMonitor> {
        IosNetworkMonitor(
            scope = get<CoroutineScope>(qualifier = APP_SCOPE),
        )
    }

    single<LifecycleMonitor> {
        IosLifecycleMonitor(
            scope = get<CoroutineScope>(qualifier = APP_SCOPE),
        )
    }
}
