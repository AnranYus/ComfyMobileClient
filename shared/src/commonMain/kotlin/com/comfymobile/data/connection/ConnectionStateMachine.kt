package com.comfymobile.data.connection

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionEffectRunner
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ConnectionStateReducer
import com.comfymobile.data.network.SideEffectIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-level driver that ties [ConnectionStateReducer] and
 * [ConnectionEffectRunner] together so the rest of the app sees a
 * single object to observe and dispatch into.
 *
 * Per the architecture sketch in
 * `docs/architecture/T0.1-comfyui-integration.md` §6 and ADR-0004:
 *  - The reducer is pure; no IO.
 *  - The runner has IO but doesn't own state.
 *  - The state machine is the lifecycle owner, holding the
 *    authoritative [ConnectionState] and feeding inputs from
 *    *both* external sources (UI, NetworkMonitor, LifecycleMonitor)
 *    and the runner's [ConnectionEffectRunner.producedInputs] back
 *    into the reducer.
 *
 * Usage from a ViewModel:
 *
 *   val machine = ConnectionStateMachine(reducer, runner, scope)
 *   machine.start()                 // begins observing runner outputs
 *   uiCallback { event -> machine.dispatch(event) }
 *   stateFlow = machine.currentState
 *
 * The class owns no platform code; it can run inside any
 * [CoroutineScope] (Compose ViewModel scope on Android, Application
 * lifecycle scope on iOS).
 */
class ConnectionStateMachine(
    private val reducer: ConnectionStateReducer,
    private val runner: ConnectionEffectRunner,
    private val scope: CoroutineScope,
    initialState: ConnectionState = ConnectionState.Connected,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)
    /** Authoritative connection state. */
    val currentState: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Direct external inputs (UI Retry button, NetworkMonitor /
     *  LifecycleMonitor adapters, etc.). Internal so callers go
     *  through [dispatch]. */
    private val externalInputs = MutableSharedFlow<ConnectionInput>(extraBufferCapacity = 64)

    /**
     * Errors emitted by the runner — re-exposed here so a single
     * Compose ViewModel can subscribe to one type per machine.
     */
    val errors: Flow<ConnectError> get() = runner.emittedErrors

    private var observerJob: Job? = null

    /**
     * Begin observing the runner's [ConnectionEffectRunner.producedInputs]
     * and the [externalInputs] channel. Idempotent — safe to call
     * multiple times; only the first call starts a job.
     */
    fun start() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            merge(runner.producedInputs, externalInputs).collect { input ->
                processInput(input)
            }
        }
    }

    /** Stop observing inputs (e.g. on ViewModel onCleared). */
    suspend fun stop() {
        observerJob?.cancel()
        observerJob = null
        runner.shutdown()
    }

    /**
     * Dispatch an external input from the UI / platform monitors.
     * Buffered (UNLIMITED-ish) so callers don't suspend; the observer
     * coroutine drains.
     */
    fun dispatch(input: ConnectionInput) {
        externalInputs.tryEmit(input)
    }

    /**
     * Convenience: tell the runner to track a prompt id as in-flight
     * so the next [SideEffectIntent.PollActiveHistory] fan-out covers
     * it. The submission flow in T1.4 part 2 will call this after a
     * successful POST /prompt.
     */
    fun trackInFlight(promptId: String) = runner.trackInFlight(promptId)

    /** Inverse of [trackInFlight]; called once a job reaches a
     *  terminal status. */
    fun untrackInFlight(promptId: String) = runner.untrackInFlight(promptId)

    /** Visible in tests / debugging. */
    fun snapshotInFlight(): Set<String> = runner.snapshotInFlight()

    private suspend fun processInput(input: ConnectionInput) {
        // Atomic state read-update plus side-effect dispatch.
        val transition = mutex.withLock {
            val transition = reducer.reduce(_state.value, input)
            _state.value = transition.next
            transition
        }
        // Effects can be dispatched outside the mutex — they don't
        // touch state directly; their results come back as new inputs.
        for (effect in transition.sideEffects) {
            runEffect(effect)
        }
    }

    private fun runEffect(intent: SideEffectIntent) {
        runner.run(intent)
    }
}
