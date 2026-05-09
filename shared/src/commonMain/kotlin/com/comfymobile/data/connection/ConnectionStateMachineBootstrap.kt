package com.comfymobile.data.connection

import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.domain.connection.LifecycleMonitor
import com.comfymobile.domain.connection.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Wires platform [NetworkMonitor] + [LifecycleMonitor] streams into
 * a [ConnectionStateMachineFacade] by translating each emission into
 * the matching [ConnectionInput] and calling
 * [ConnectionStateMachineFacade.dispatch].
 *
 * Lives outside both the state machine (which is pure) and the UI
 * (which is platform-agnostic). The DI module instantiates one of
 * these alongside the state machine and starts it during app boot.
 *
 * The bootstrap deduplicates emissions via `distinctUntilChanged`
 * so a noisy platform monitor (Android `NetworkCallback` can fire
 * multiple times for the same network) doesn't flood the state
 * machine with redundant inputs.
 */
class ConnectionStateMachineBootstrap(
    private val machine: ConnectionStateMachineFacade,
    private val networkMonitor: NetworkMonitor,
    private val lifecycleMonitor: LifecycleMonitor,
    private val scope: CoroutineScope,
) {

    private var networkJob: Job? = null
    private var lifecycleJob: Job? = null

    /**
     * Begin observing the monitors and dispatching inputs. Idempotent
     * — repeated calls are a no-op while observers are already alive.
     */
    fun start() {
        if (networkJob == null) {
            networkJob = scope.launch {
                networkMonitor.state
                    .distinctUntilChanged()
                    .onEach { state ->
                        machine.dispatch(
                            ConnectionInput.Network(online = state.online, wifi = state.wifi)
                        )
                    }
                    .collect { /* drained via onEach */ }
            }
        }
        if (lifecycleJob == null) {
            lifecycleJob = scope.launch {
                lifecycleMonitor.foregrounded
                    .distinctUntilChanged()
                    .onEach { foregrounded ->
                        machine.dispatch(ConnectionInput.Lifecycle(foregrounded = foregrounded))
                    }
                    .collect { /* drained via onEach */ }
            }
        }
    }

    /** Cancel both observer jobs. */
    fun stop() {
        networkJob?.cancel()
        networkJob = null
        lifecycleJob?.cancel()
        lifecycleJob = null
    }
}
