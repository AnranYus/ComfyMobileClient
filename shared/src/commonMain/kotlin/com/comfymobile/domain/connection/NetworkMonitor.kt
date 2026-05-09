package com.comfymobile.domain.connection

import kotlinx.coroutines.flow.Flow

/**
 * Platform-supplied stream of network reachability snapshots.
 *
 * Observed by [com.comfymobile.data.connection.ConnectionStateMachineBootstrap]
 * to feed [com.comfymobile.data.network.ConnectionInput.Network] into
 * the connection state machine. The flow is **hot** in the sense
 * that subscribers should always receive the *current* snapshot
 * immediately on collect; production implementations achieve this
 * via `StateFlow` or `MutableSharedFlow(replay = 1)`.
 *
 * Production implementations (Android / iOS) live in T1.4b part 3c
 * alongside the DI module — they need platform `Context` /
 * `NWPathMonitor` and aren't suitable for commonMain.
 *
 * For tests and as a "no platform integration" fallback,
 * [AlwaysOnlineNetworkMonitor] in `data/connection/` provides a
 * stream that always reports online + Wi-Fi.
 */
interface NetworkMonitor {

    /**
     * Snapshot stream of `(online, wifi)` pairs.
     *
     *   online = the device has *any* active network (Wi-Fi, cellular, ethernet…)
     *   wifi   = the active network is Wi-Fi specifically
     *
     * The pair is emitted as a single value rather than two separate
     * Flows because the state-machine bootstrap consumes both fields
     * together.
     */
    val state: Flow<NetworkState>
}

/**
 * Snapshot value emitted by [NetworkMonitor.state]. Plain data so
 * callers don't depend on platform types.
 */
data class NetworkState(
    val online: Boolean,
    val wifi: Boolean,
)
