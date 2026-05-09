package com.comfymobile.domain.connection

import kotlinx.coroutines.flow.Flow

/**
 * Platform-supplied stream of foreground / background transitions.
 *
 * Observed by [com.comfymobile.data.connection.ConnectionStateMachineBootstrap]
 * to feed [com.comfymobile.data.network.ConnectionInput.Lifecycle]
 * into the connection state machine — most importantly so the
 * machine knows when a backgrounded WS drop should be classified
 * as Branch C (BACKGROUND_RESUMED) on the next foreground.
 *
 * Production implementations:
 *   Android: ProcessLifecycleOwner.lifecycle
 *   iOS:     UIApplication willEnterForeground / didEnterBackground
 * land in T1.4b part 3c alongside DI. For tests + fallback,
 * [AlwaysForegroundedLifecycleMonitor] always reports foreground.
 */
interface LifecycleMonitor {

    /**
     * Snapshot stream of `foregrounded` booleans. Production
     * implementations use `StateFlow` so subscribers always see the
     * current value.
     */
    val foregrounded: Flow<Boolean>
}
