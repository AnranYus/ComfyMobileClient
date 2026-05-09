package com.comfymobile.data.di

import com.comfymobile.data.connection.ConnectionStateMachine
import com.comfymobile.data.connection.ConnectionStateMachineBootstrap
import com.comfymobile.data.descriptor.NodeDescriptorLoader
import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * Process-lifetime "boot the shared runtime" helper, called once per
 * process from the platform entry point (Android
 * `ComfyMobileApplication.onCreate`, iOS `bootKoinIos()`).
 *
 * Wraps three things behind a single public function so the platform
 * app modules don't have to reach into the shared module's internal
 * Koin qualifiers (per @Lily PR #19 review comment `4413981846`
 * blocker 1 — `:androidApp` cannot access [APP_SCOPE] when it's
 * `internal`):
 *  1. Start the connection state machine. Idempotent.
 *  2. Start the platform-monitor bootstrap. Idempotent.
 *  3. Async-load [NodeDescriptorRegistry] from
 *     `composeResources/files/node-descriptors/v1.json` and
 *     `koin.declare()` it on completion so Phase-2 consumers can
 *     inject it.
 *
 * The async load runs on the [APP_SCOPE] coroutine scope; that scope
 * is process-scoped so the load survives Activity recreation.
 *
 * Per @Lily PR #18 thread (`62385887`): no `init { start() }` —
 * lifecycle handoff stays explicit and process-bound.
 */
fun bootSharedRuntime(koin: Koin) {
    // Both `start()` calls are idempotent so a (rare) duplicate
    // invocation under hot-reload won't spawn duplicate observers.
    koin.get<ConnectionStateMachine>().start()
    koin.get<ConnectionStateMachineBootstrap>().start()
    // Best-effort guard against duplicate registry-load coroutines.
    // Platform bootstraps (`ComfyMobileApplication`, `bootKoinIos`)
    // already guard against double-init, but if this function is
    // called twice anyway, skip a second load + declare. Per
    // @Lily PR #19 review comment `9005f999`.
    if (koin.getOrNull<NodeDescriptorRegistry>() == null) {
        koin.get<CoroutineScope>(qualifier = APP_SCOPE).launch {
            val registry = NodeDescriptorLoader.load()
            koin.declare<NodeDescriptorRegistry>(registry)
        }
    }
}
