package com.comfymobile.data.connection

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.domain.connection.LifecycleMonitor
import com.comfymobile.domain.connection.NetworkMonitor
import com.comfymobile.domain.connection.NetworkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStateMachineBootstrapTest {

    /**
     * Capturing facade — records every dispatched input so tests
     * can assert exactly what the bootstrap emitted.
     */
    private class CapturingFacade : ConnectionStateMachineFacade {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val currentState: StateFlow<ConnectionState> = _state.asStateFlow()
        override val errors: Flow<ConnectError> = MutableSharedFlow(replay = 0)
        val dispatched = mutableListOf<ConnectionInput>()
        override fun dispatch(input: ConnectionInput) {
            dispatched += input
        }
    }

    private class FakeNetworkMonitor : NetworkMonitor {
        val source = MutableStateFlow(NetworkState(online = true, wifi = true))
        override val state: Flow<NetworkState> = source
    }

    private class FakeLifecycleMonitor : LifecycleMonitor {
        val source = MutableStateFlow(true)
        override val foregrounded: Flow<Boolean> = source
    }

    @Test fun start_dispatches_initial_NetworkMonitor_snapshot() = runTest {
        val facade = CapturingFacade()
        val net = FakeNetworkMonitor()
        val life = FakeLifecycleMonitor()
        val bootstrap = ConnectionStateMachineBootstrap(facade, net, life, this)
        bootstrap.start()
        runCurrent()
        // Initial Network input + initial Lifecycle input — order
        // doesn't matter, both must be present.
        val networkDispatches = facade.dispatched.filterIsInstance<ConnectionInput.Network>()
        val lifecycleDispatches = facade.dispatched.filterIsInstance<ConnectionInput.Lifecycle>()
        assertEquals(1, networkDispatches.size)
        assertEquals(true, networkDispatches.single().online)
        assertEquals(true, networkDispatches.single().wifi)
        assertEquals(1, lifecycleDispatches.size)
        assertEquals(true, lifecycleDispatches.single().foregrounded)
        bootstrap.stop()
    }

    @Test fun network_changes_dispatch_new_inputs() = runTest {
        val facade = CapturingFacade()
        val net = FakeNetworkMonitor()
        val life = FakeLifecycleMonitor()
        val bootstrap = ConnectionStateMachineBootstrap(facade, net, life, this)
        bootstrap.start()
        runCurrent()

        net.source.value = NetworkState(online = true, wifi = false) // switched to cellular
        runCurrent()
        net.source.value = NetworkState(online = false, wifi = false) // network lost
        runCurrent()

        val networkDispatches = facade.dispatched.filterIsInstance<ConnectionInput.Network>()
        assertEquals(3, networkDispatches.size)
        assertEquals(NetworkState(true, true), networkDispatches[0].toState())
        assertEquals(NetworkState(true, false), networkDispatches[1].toState())
        assertEquals(NetworkState(false, false), networkDispatches[2].toState())
        bootstrap.stop()
    }

    @Test fun duplicate_NetworkMonitor_emissions_are_deduped() = runTest {
        val facade = CapturingFacade()
        val net = FakeNetworkMonitor()
        val life = FakeLifecycleMonitor()
        val bootstrap = ConnectionStateMachineBootstrap(facade, net, life, this)
        bootstrap.start()
        runCurrent()

        // Emitting the SAME state — Android's ConnectivityManager
        // does this when the network re-validates without changing.
        net.source.value = NetworkState(online = true, wifi = true)
        net.source.value = NetworkState(online = true, wifi = true)
        runCurrent()

        // Initial emission only; duplicates dropped by
        // distinctUntilChanged.
        assertEquals(1, facade.dispatched.filterIsInstance<ConnectionInput.Network>().size)
        bootstrap.stop()
    }

    @Test fun lifecycle_changes_dispatch_new_inputs() = runTest {
        val facade = CapturingFacade()
        val net = FakeNetworkMonitor()
        val life = FakeLifecycleMonitor()
        val bootstrap = ConnectionStateMachineBootstrap(facade, net, life, this)
        bootstrap.start()
        runCurrent()

        life.source.value = false // backgrounded
        runCurrent()
        life.source.value = true // foregrounded again
        runCurrent()

        val lifecycleDispatches = facade.dispatched.filterIsInstance<ConnectionInput.Lifecycle>()
        assertEquals(3, lifecycleDispatches.size)
        assertEquals(true, lifecycleDispatches[0].foregrounded)
        assertEquals(false, lifecycleDispatches[1].foregrounded)
        assertEquals(true, lifecycleDispatches[2].foregrounded)
        bootstrap.stop()
    }

    @Test fun start_is_idempotent() = runTest {
        val facade = CapturingFacade()
        val net = FakeNetworkMonitor()
        val life = FakeLifecycleMonitor()
        val bootstrap = ConnectionStateMachineBootstrap(facade, net, life, this)
        bootstrap.start()
        bootstrap.start()
        bootstrap.start()
        runCurrent()
        // Three start() calls + one initial emission per monitor →
        // still only one Network and one Lifecycle dispatch.
        assertEquals(1, facade.dispatched.filterIsInstance<ConnectionInput.Network>().size)
        assertEquals(1, facade.dispatched.filterIsInstance<ConnectionInput.Lifecycle>().size)
        bootstrap.stop()
    }

    @Test fun stop_then_start_resumes_observation() = runTest {
        val facade = CapturingFacade()
        val net = FakeNetworkMonitor()
        val life = FakeLifecycleMonitor()
        val bootstrap = ConnectionStateMachineBootstrap(facade, net, life, this)
        bootstrap.start()
        runCurrent()
        bootstrap.stop()
        // Emit while stopped — must NOT be picked up.
        net.source.value = NetworkState(online = false, wifi = false)
        runCurrent()
        val beforeRestart = facade.dispatched.size
        // Restart and re-emit something different.
        bootstrap.start()
        runCurrent()
        net.source.value = NetworkState(online = true, wifi = true)
        runCurrent()
        // After restart the bootstrap should emit at least the
        // current snapshot (false,false) immediately on subscribe,
        // then the (true,true) change.
        assertTrue(facade.dispatched.size > beforeRestart)
        bootstrap.stop()
    }

    @Test fun AlwaysOnlineNetworkMonitor_default_is_online_and_wifi() = runTest {
        val monitor = AlwaysOnlineNetworkMonitor()
        // Bounded collect via first() — avoids leaving an infinite
        // collector running past the test body. (Per @Lily PR #15
        // review msg `984bed0d`.)
        val first = monitor.state.first()
        assertEquals(NetworkState(online = true, wifi = true), first)
    }

    @Test fun AlwaysForegroundedLifecycleMonitor_default_is_true() = runTest {
        val monitor = AlwaysForegroundedLifecycleMonitor()
        val first = monitor.foregrounded.first()
        assertEquals(true, first)
    }

    private fun ConnectionInput.Network.toState(): NetworkState =
        NetworkState(online = online, wifi = wifi)
}
