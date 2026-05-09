package com.comfymobile

import com.comfymobile.data.connection.ConnectionStateMachine
import com.comfymobile.data.connection.ConnectionStateMachineBootstrap
import com.comfymobile.data.descriptor.NodeDescriptorLoader
import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.data.di.APP_SCOPE
import com.comfymobile.data.di.appModule
import com.comfymobile.data.di.iosPlatformModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
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
 * Idempotent: calling twice is a no-op (e.g. if SwiftUI re-runs the
 * `App.init` somehow under hot-reload).
 *
 * Exposed under the Swift name `IosBootstrapKt.bootKoinIos()`.
 */
@Suppress("FunctionName", "unused")
fun bootKoinIos() {
    if (GlobalContext.getOrNull() != null) return

    startKoin {
        modules(
            appModule(),
            iosPlatformModule(),
        )
    }

    val koin = GlobalContext.get()
    koin.get<ConnectionStateMachine>().start()
    koin.get<ConnectionStateMachineBootstrap>().start()

    // Async load of the descriptor registry; declared into Koin once
    // available so Phase-2 consumers can inject it.
    koin.get<CoroutineScope>(qualifier = APP_SCOPE).launch {
        val registry = NodeDescriptorLoader.load()
        GlobalContext.get().declare<NodeDescriptorRegistry>(registry)
    }
}
