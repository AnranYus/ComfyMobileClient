package com.comfymobile.android

import android.app.Application
import com.comfymobile.data.connection.ConnectionStateMachine
import com.comfymobile.data.connection.ConnectionStateMachineBootstrap
import com.comfymobile.data.descriptor.NodeDescriptorLoader
import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.data.di.APP_SCOPE
import com.comfymobile.data.di.androidPlatformModule
import com.comfymobile.data.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 *  2. Start the connection state machine once (idempotent).
 *  3. Start the platform-monitor bootstrap once (idempotent).
 *  4. Asynchronously load [NodeDescriptorRegistry] from
 *     `Res.readBytes` (`composeResources/files/node-descriptors/v1.json`)
 *     and `koin.declare()` it so later consumers (workflow editor,
 *     T1.5 dependents) can inject it.
 *
 * Activity recreation never touches any of the above — it only
 * re-renders the Compose tree.
 *
 * Per @Lily PR #19 review comment `4413957569` blocker 1: the
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

        val koin = app.koin

        // Start the state machine + monitor bootstrap. Both are
        // idempotent (PR #18 thread `62385887` review constraint),
        // so a hot-reload of the process via debugger is safe.
        koin.get<ConnectionStateMachine>().start()
        koin.get<ConnectionStateMachineBootstrap>().start()

        // Load descriptor registry off the main thread; declare into
        // Koin once available. No consumer in 3d-ii's scope reads it,
        // but Phase 2 workflow editor will.
        koin.get<CoroutineScope>(qualifier = APP_SCOPE).launch {
            val registry = NodeDescriptorLoader.load()
            koin.declare<NodeDescriptorRegistry>(registry)
        }
    }
}
