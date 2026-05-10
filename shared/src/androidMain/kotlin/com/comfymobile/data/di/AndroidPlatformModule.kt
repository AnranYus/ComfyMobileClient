package com.comfymobile.data.di

import android.content.Context
import com.comfymobile.data.connection.AndroidLifecycleMonitor
import com.comfymobile.data.connection.AndroidNetworkMonitor
import com.comfymobile.data.platform.PlatformContext
import com.comfymobile.domain.connection.LifecycleMonitor
import com.comfymobile.domain.connection.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-side Koin platform module.
 *
 * Built once at app cold-start in `MainActivity` (or an
 * `Application` subclass) — supplied with the application
 * [Context] so the platform monitors can register OS callbacks
 * against it.
 *
 * Bound here:
 *  - [PlatformContext] → wraps the Application context so
 *    commonMain factories (`createSqlDriver`, `createSettings`)
 *    can resolve through DI without expect/actual import surface.
 *  - [NetworkMonitor] → [AndroidNetworkMonitor] using
 *    `ConnectivityManager.NetworkCallback` registered against the
 *    [APP_SCOPE] coroutine scope so the underlying registration
 *    happens exactly once for the process.
 *  - [LifecycleMonitor] → [AndroidLifecycleMonitor] backed by
 *    `ProcessLifecycleOwner` so it follows the *process* lifetime,
 *    not the activity lifetime — rotation must NOT cancel the
 *    state machine (per @Lily PR #18 thread `62385887`).
 *
 * Usage from `MainActivity.onCreate`:
 *
 *   startKoin {
 *       modules(
 *           appModule(),
 *           androidPlatformModule(applicationContext),
 *       )
 *   }
 */
fun androidPlatformModule(applicationContext: Context): Module = module {

    single<PlatformContext> { PlatformContext(androidContext = applicationContext) }

    single<NetworkMonitor> {
        AndroidNetworkMonitor(
            context = applicationContext,
            scope = get<CoroutineScope>(qualifier = APP_SCOPE),
        )
    }

    single<LifecycleMonitor> {
        AndroidLifecycleMonitor(
            scope = get<CoroutineScope>(qualifier = APP_SCOPE),
        )
    }
}
