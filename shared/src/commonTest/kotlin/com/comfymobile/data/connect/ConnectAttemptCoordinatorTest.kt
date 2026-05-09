package com.comfymobile.data.connect

import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.network.ComfyHttpException
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.persistence.InMemoryServerHistoryStore
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coordinator-level tests run **fully on the `runTest` scheduler** by
 * substituting [SystemStatsProbe] with a plain suspending lambda
 * (per @Lily PR #18 review comment `4413882022` — Ktor's MockEngine
 * dispatches probes onto a thread pool whose progress is opaque to
 * `runTest`'s virtual time, so coordinator tests that drive an
 * `HttpClient(MockEngine)` were inherently flaky no matter which
 * side-effect they awaited).
 *
 * Ktor-layer status / parsing behaviour is exercised separately in
 * `ComfyHttpClientTest`.
 *
 * Synchronisation pattern:
 *  - `runCurrent()` after `vm.onSubmit()` drains the channel send +
 *    the coordinator's `onEach` body + the synchronous probe lambda
 *    + the synchronous side effects (`historyStore.upsert`,
 *    `activeServer.setActive`, `machine.dispatch`).
 *  - Then assertions read the snapshot directly:
 *    `activeServer.current.value`, `store.getById(...)`,
 *    `facade.dispatched.value`.
 *
 * Cleanup (`coord.stop()` + `coroutineContext.cancelChildren()`) is
 * always in `finally` so an assertion failure still tears the
 * `stateIn(SharingStarted.Eagerly)` collectors of [ConnectViewModel]
 * down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectAttemptCoordinatorTest {

    /** Records dispatched inputs as a [StateFlow] for snapshot reads. */
    private class CapturingFacade : ConnectionStateMachineFacade {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val currentState: StateFlow<ConnectionState> = _state.asStateFlow()
        override val errors: Flow<ConnectError> = MutableSharedFlow(replay = 0)
        private val _dispatched = MutableStateFlow<List<ConnectionInput>>(emptyList())
        val dispatched: StateFlow<List<ConnectionInput>> = _dispatched.asStateFlow()
        override fun dispatch(input: ConnectionInput) {
            _dispatched.value = _dispatched.value + input
        }
    }

    private fun successProbe(): SystemStatsProbe = SystemStatsProbe { /* no-op = success */ }

    private fun probeThrowing(error: Throwable): SystemStatsProbe = SystemStatsProbe { throw error }

    @Test fun success_probe_upserts_server_sets_active_dispatches_Retry() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val activeServer = ActiveServerHolder()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = activeServer,
            scope = this,
            nowEpochMs = { 1234L },
            probe = successProbe(),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onFriendlyNameChanged("MacBook")
            vm.onSubmit()
            // Drain the test scheduler — channel receive +
            // coordinator onEach + synchronous probe + synchronous
            // side effects all run here.
            runCurrent()

            // History upserted.
            val saved = store.getById("192.168.1.10:8188")
            assertNotNull(saved)
            assertEquals("MacBook", saved.label)
            assertEquals(1234L, saved.lastConnectedAtEpochMs)

            // Active server pointer set.
            assertEquals(saved, activeServer.current.value)

            // Retry dispatched, no ConnectAttempt failure dispatched.
            assertTrue(
                facade.dispatched.value.none { it is ConnectionInput.ConnectAttempt },
                "Expected no ConnectAttempt dispatch on success, got: ${facade.dispatched.value}",
            )
            assertTrue(
                facade.dispatched.value.contains(ConnectionInput.Retry),
                "Expected Retry dispatch on success, got: ${facade.dispatched.value}",
            )
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun successful_probe_when_in_Lost_state_dispatches_Retry_to_recover() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1234L },
            probe = successProbe(),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            runCurrent()
            assertTrue(
                facade.dispatched.value.contains(ConnectionInput.Retry),
                "Expected Retry dispatch, got: ${facade.dispatched.value}",
            )
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun probe_with_404_dispatches_WRONG_PORT_404() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1234L },
            probe = probeThrowing(ComfyHttpException.HttpStatus(statusCode = 404)),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            runCurrent()

            val attempts = facade.dispatched.value
                .filterIsInstance<ConnectionInput.ConnectAttempt>()
            assertEquals(1, attempts.size, "Got: ${facade.dispatched.value}")
            assertEquals(ConnectError.WRONG_PORT_404, attempts.single().classified)
            // Server NOT saved on failure.
            assertEquals(null, store.getById("192.168.1.10:8188"))
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun probe_with_non_comfyui_body_dispatches_NOT_COMFYUI() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1234L },
            // ComfyHttpClient.getSystemStats throws MissingField when
            // /system_stats parses but `system.comfyui_version` is
            // absent — the canonical "not ComfyUI" signal.
            probe = probeThrowing(
                ComfyHttpException.MissingField(
                    endpoint = "/system_stats",
                    field = "system.comfyui_version",
                ),
            ),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            runCurrent()

            val attempts = facade.dispatched.value
                .filterIsInstance<ConnectionInput.ConnectAttempt>()
            assertEquals(1, attempts.size, "Got: ${facade.dispatched.value}")
            assertEquals(ConnectError.NOT_COMFYUI, attempts.single().classified)
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun probe_failure_does_NOT_set_active_server() = runTest {
        // Per @Lily PR #18 review: active server is only set on
        // verified probe success — never from a failed attempt.
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val activeServer = ActiveServerHolder()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = activeServer,
            scope = this,
            nowEpochMs = { 1L },
            probe = probeThrowing(ComfyHttpException.HttpStatus(statusCode = 404)),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            runCurrent()
            assertEquals(null, activeServer.current.value)
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun coordinator_propagates_CancellationException() = runTest {
        // Regression: per @Lily PR #18 review (msg `75c88c17`),
        // `catch (t: Throwable)` must not swallow CancellationException
        // (would break structured concurrency — the VM scope cancelling
        // the coordinator must propagate to the in-flight probe).
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1L },
            probe = probeThrowing(CancellationException("test cancel")),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            runCurrent()
            // Coordinator's launchIn job should be cancelled by the
            // propagated CancellationException; no ConnectAttempt
            // dispatched (because the probe was cancelled, not failed).
            assertTrue(
                facade.dispatched.value
                    .filterIsInstance<ConnectionInput.ConnectAttempt>().isEmpty(),
                "Expected NO ConnectAttempt on cancellation, got: ${facade.dispatched.value}",
            )
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun submit_with_empty_friendly_name_falls_back_to_host_label() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val activeServer = ActiveServerHolder()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            activeServer = activeServer,
            scope = this,
            nowEpochMs = { 1L },
            probe = successProbe(),
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            // No friendly name set.
            vm.onSubmit()
            runCurrent()
            assertEquals("192.168.1.10", activeServer.current.value?.label)
            assertEquals("192.168.1.10", store.getById("192.168.1.10:8188")?.label)
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }
}
