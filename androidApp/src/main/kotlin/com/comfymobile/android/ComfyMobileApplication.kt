package com.comfymobile.android

import android.app.Application
import com.comfymobile.data.di.androidPlatformModule
import com.comfymobile.data.di.appModule
import com.comfymobile.data.di.bootSharedRuntime
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin

/**
 * Process-lifetime Application class.
 *
 * Per @Lily PR #18 thread (`62385887`) the bootstrap must follow the
 * *process* lifetime, not the Activity lifetime — rotation must NOT
 * cancel the state machine. Hooking everything into `Application`
 * gives us that guarantee:
 *
 *  1. Start Koin once with [appModule] + [androidPlatformModule].
 *  2. Hand the resulting [Koin] to [bootSharedRuntime] which starts
 *     the state machine + monitor bootstrap and async-loads the
 *     descriptor registry. The shared helper hides the internal
 *     scope qualifier so this app module never reaches into shared
 *     internals (per @Lily PR #19 review `4413981846` blocker 1).
 *
 * Activity recreation never touches any of the above — it only
 * re-renders the Compose tree.
 *
 * Per @Lily PR #19 review (`4413957569`) blocker 1: the
 * [KoinApplication] returned by [startKoin] is held directly so the
 * iOS K/N target compiles uniformly with the same pattern (no
 * `GlobalContext` lookup).
 *
 * Registered via `<application android:name>` in `AndroidManifest.xml`.
 */
class ComfyMobileApplication : Application() {

    private var koinApp: KoinApplication? = null

    override fun onCreate() {
        super.onCreate()

        if (koinApp != null) return

        val app = startKoin {
            modules(
                appModule(),
                androidPlatformModule(applicationContext),
            )
        }
        koinApp = app

        bootSharedRuntime(app.koin)
    }
}
