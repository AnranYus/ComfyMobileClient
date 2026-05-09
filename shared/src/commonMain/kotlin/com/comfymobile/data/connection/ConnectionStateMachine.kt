package com.comfymobile.data.connection

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionEffectRunner
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ConnectionStateReducer
import com.comfymobile.data.network.SideEffectIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI-facing surface of the connection layer. The Compose UI in
 * `presentation/connection/` consumes a [ConnectionStateMachineFacade]
 * — never the concrete [ConnectionStateMachine] — so:
 *  1. Compose previews / unit tests can substitute a fake (e.g.
 *     `FakeConnectionStateMachine` in T1.4b part 2 tests) without
 *     depending on Ktor / coroutines / runner internals.
 *  2. The lifecycle methods (`start`, `stop`) and the in-flight
 *     bookkeeping (`trackInFlight`, etc.) stay scoped to the
 *     ViewModel that owns the lifecycle; UI can't accidentally
 *     mis-use them.
 *
 * Production binding: [ConnectionStateMachine] implements this
 * facade. The DI module (T1.4b part 3) provides the same instance
 * under both types — typing UI dependencies as the facade keeps the
 * compile boundary clean.
 */
interface ConnectionStateMachineFacade {
    val currentState: StateFlow<ConnectionState>
    val errors: Flow<ConnectError>
    fun dispatch(input: ConnectionInput)
}

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
) : ConnectionStateMachineFacade {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)
    /** Authoritative connection state. */
    override val currentState: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * Direct external inputs (UI Retry button, NetworkMonitor /
     *  LifecycleMonitor adapters, etc.). Internal so callers go
     *  through [dispatch].
     *
     *  Backed by an UNLIMITED [Channel] (NOT a `MutableSharedFlow` /
     *  `tryEmit`) — `MutableSharedFlow(replay=0)` drops emissions
     *  while there are zero subscribers, which means a `dispatch()`
     *  arriving before the observer coroutine has actually started
     *  collecting would silently disappear. Buffering through a
     *  Channel means an early `dispatch()` (e.g. a quick UI Retry
     *  while `start()` is still launching) is enqueued and consumed
     *  the moment the collector activates. (Per @Lily PR #12 review,
     *  msg `335b8813`.)
     */
    private val externalInputs = Channel<ConnectionInput>(Channel.UNLIMITED)

    /**
     * Errors emitted by the runner — re-exposed here so a single
     * Compose ViewModel can subscribe to one type per machine.
     */
    override val errors: Flow<ConnectError> get() = runner.emittedErrors

    private var observerJob: Job? = null

    /**
     * Begin observing the runner's [ConnectionEffectRunner.producedInputs]
     * and the [externalInputs] channel. Idempotent — safe to call
     * multiple times; only the first call starts a job.
     */
    fun start() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            merge(runner.producedInputs, externalInputs.receiveAsFlow()).collect { input ->
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
     * The Channel is UNLIMITED so this never suspends and never
     * drops; safe to call from a Compose `onClick` handler or a
     * platform broadcast receiver.
     */
    override fun dispatch(input: ConnectionInput) {
        externalInputs.trySend(input)
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
