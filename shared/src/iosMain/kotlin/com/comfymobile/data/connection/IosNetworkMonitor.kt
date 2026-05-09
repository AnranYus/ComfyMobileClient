package com.comfymobile.data.connection

import com.comfymobile.domain.connection.NetworkMonitor
import com.comfymobile.domain.connection.NetworkState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create

/**
 * iOS implementation of [NetworkMonitor] backed by `NWPathMonitor`.
 * Emits one [NetworkState] per path update from the `Network`
 * framework.
 *
 * The DI module supplies a long-lived [scope]; the underlying
 * monitor is registered exactly once and the resulting stateFlow
 * is shared across subscribers.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNetworkMonitor(
    scope: CoroutineScope,
) : NetworkMonitor {

    private val rawState: Flow<NetworkState> = callbackFlow {
        val monitor = nw_path_monitor_create()
        val queue = dispatch_queue_create("comfy.network-monitor", null)
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path ->
            val online = nw_path_get_status(path) == nw_path_status_satisfied
            val wifi = nw_path_uses_interface_type(path, nw_interface_type_wifi)
            trySend(NetworkState(online = online, wifi = wifi))
        }
        nw_path_monitor_start(monitor)

        awaitClose {
            nw_path_monitor_cancel(monitor)
        }
    }.distinctUntilChanged()

    override val state: Flow<NetworkState> = rawState.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        // We don't have a synchronous initial snapshot from
        // NWPathMonitor; assume online + Wi-Fi until the first
        // update lands. The reducer treats both as the same
        // "Connected stays Connected" branch so this is safe.
        initialValue = NetworkState(online = true, wifi = true),
    )
}
