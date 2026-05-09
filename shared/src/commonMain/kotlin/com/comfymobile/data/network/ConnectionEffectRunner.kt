package com.comfymobile.data.network

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Translates pure [SideEffectIntent] descriptors emitted by
 * [ConnectionStateReducer] into actual coroutines.
 *
 * The reducer remains pure (zero IO, zero clock, zero network); the
 * runner does:
 *  - [SideEffectIntent.OpenWs]            → starts a WS session via [ComfyWebSocketClient] and forwards each frame as [ConnectionInput.Ws] back into [producedInputs]
 *  - [SideEffectIntent.PollHistory]       → calls [ComfyHttpClient.getHistoryEntry] and emits [ConnectionInput.HistoryProbe]
 *  - [SideEffectIntent.PollActiveHistory] → fans out a [SideEffectIntent.PollHistory] per id in [activePromptIds]
 *  - [SideEffectIntent.ScheduleTimer]     → coroutine [delay] then emits [ConnectionInput.Timer]
 *  - [SideEffectIntent.CancelTimer]       → cancels the corresponding job
 *  - [SideEffectIntent.EmitError]         → forwards on [emittedErrors] for the UI to consume
 *
 * ### Active-server awareness (T1.4b part 3d-ii, @Lily PR #18 thread)
 *
 * The runner does NOT capture a single `ComfyHttpClient` /
 * `WebSocketSource` at construction. Instead it takes:
 *  - [activeServer]: [ActiveServerHolder] — the source of truth for
 *    which server the user is currently connected to.
 *  - [httpClientFactory] / [webSocketSourceFactory]: per-server
 *    factories invoked at effect-run time so each effect uses the
 *    *current* active server's baseUrl, not a stale one.
 *
 * Two contract invariants come from this design:
 *  1. **No active server → explicit error, no IO.** Any side effect
 *     that needs a server (`OpenWs`, `PollHistory`, `PollActiveHistory`)
 *     while [ActiveServerHolder.current] is `null` emits
 *     [ConnectError.NO_ACTIVE_SERVER] on [emittedErrors] and does
 *     **not** route to a default URL or any previously-active server.
 *     Per @Lily / @Ores final convergence (PR #18 thread msg
 *     `b522a9f3` / `60a7e64a`).
 *  2. **Active-server change cancels in-flight IO.** A server-observer
 *     job watches [ActiveServerHolder.current] and cancels the WS
 *     session and any in-flight history-poll jobs whenever the active
 *     server changes (or becomes null), so a switch to a new server
 *     never leaves a half-open WS or pending poll talking to the old
 *     baseUrl.
 *
 * Tests inject a `CoroutineScope` backed by `TestScope` and drive the
 * runner with virtual time, asserting on the inputs the runner sends
 * back into [producedInputs].
 */
class ConnectionEffectRunner(
    private val activeServer: ActiveServerHolder,
    private val httpClientFactory: (ServerInfo) -> ComfyHttpClient,
    private val webSocketSourceFactory: (ServerInfo) -> WebSocketSource,
    private val scope: CoroutineScope,
) {

    /**
     * Mutable list of prompt ids the runtime considers in-flight.
     * The reducer doesn't know what's in flight (that's a runtime
     * concern), so the runner holds it. Updated externally via
     * [trackInFlight] / [untrackInFlight]; consulted when the
     * reducer emits [SideEffectIntent.PollActiveHistory].
     */
    private val activePromptIds = mutableSetOf<String>()

    /** Inputs produced by side effects, forwarded into the reducer
     *  by the state-machine driver. */
    val producedInputs: Flow<ConnectionInput>
        get() = producedChannel.receiveAsFlow()

    /** Errors classified during reconnect; UI consumes this to pick
     *  the appropriate copy in T1.4. */
    val emittedErrors: Flow<ConnectError>
        get() = errorFlow.asSharedFlow()

    private val producedChannel = Channel<ConnectionInput>(Channel.UNLIMITED)
    /**
     * `replay = 1` so the latest classified error is delivered to a
     * subscriber that arrives after the runner has already emitted —
     * UIs typically subscribe asynchronously after the connect flow
     * already failed once. With `replay = 0` the error would be lost,
     * leading to a stuck UI that knows the connection is broken but
     * has no specific reason to display. (Caught by @Lily PR #6
     * review, msg `0e87febf`.)
     */
    private val errorFlow = MutableSharedFlow<ConnectError>(replay = 1, extraBufferCapacity = 16)
    private val timerJobs = mutableMapOf<TimerTick, Job>()
    private var wsJob: Job? = null
    /**
     * In-flight history poll jobs. Tracked so an active-server change
     * can cancel polls that would otherwise resolve against the old
     * baseUrl. Each job removes itself on completion.
     */
    private val pollJobs = mutableSetOf<Job>()

    /** Server observer; cancels server-bound IO on active-server change. */
    private var serverObserverJob: Job? = scope.launch {
        // `drop(1)` because the StateFlow's initial value is the
        // current snapshot — we only react to *changes*. Without this
        // we would cancel nothing on first emission anyway, but the
        // semantics are clearer by skipping it explicitly.
        var lastObservedServerId: String? = activeServer.current.value?.serverId
        activeServer.current.drop(1).collect { newServer ->
            val newId = newServer?.serverId
            if (newId != lastObservedServerId) {
                cancelServerBoundIo()
                lastObservedServerId = newId
            }
        }
    }

    fun trackInFlight(promptId: String) {
        activePromptIds.add(promptId)
    }

    fun untrackInFlight(promptId: String) {
        activePromptIds.remove(promptId)
    }

    /** For testability / introspection. */
    fun snapshotInFlight(): Set<String> = activePromptIds.toSet()

    fun run(intents: List<SideEffectIntent>) {
        intents.forEach { run(it) }
    }

    fun run(intent: SideEffectIntent) {
        when (intent) {
            is SideEffectIntent.OpenWs -> withActiveServerOrEmit { server ->
                openWs(server, intent.clientId)
            }
            is SideEffectIntent.PollHistory -> withActiveServerOrEmit { server ->
                pollHistory(server, intent.promptId)
            }
            SideEffectIntent.PollActiveHistory -> withActiveServerOrEmit { server ->
                activePromptIds.toList().forEach { pollHistory(server, it) }
            }
            // Timers are protocol-level (reconnect cadence); they do
            // not depend on the active server, so we run them
            // unconditionally.
            is SideEffectIntent.ScheduleTimer -> scheduleTimer(intent.tick, intent.millis)
            is SideEffectIntent.CancelTimer -> cancelTimer(intent.tick)
            is SideEffectIntent.EmitError -> {
                errorFlow.tryEmit(intent.error)
            }
        }
    }

    /** Cancels in-flight timers + WS job. Use when the surrounding
     *  scope is being torn down (e.g. user signs out / app exits). */
    suspend fun shutdown() {
        serverObserverJob?.cancelAndJoin()
        serverObserverJob = null
        timerJobs.values.forEach { runCatching { it.cancelAndJoin() } }
        timerJobs.clear()
        wsJob?.cancelAndJoin()
        wsJob = null
        val pollSnapshot = pollJobs.toList()
        pollJobs.clear()
        pollSnapshot.forEach { runCatching { it.cancelAndJoin() } }
    }

    // ----------------------------------------------------------------- private

    /**
     * Snapshot [ActiveServerHolder.current] and either call [block]
     * with the resolved [ServerInfo] or — when no server is active —
     * emit [ConnectError.NO_ACTIVE_SERVER] without performing any IO.
     *
     * Per @Lily PR #18 thread msg `60a7e64a`: this is a first-class
     * user-facing error path, NOT a silent no-op. The state machine
     * reducer transitions to `Lost(NO_ACTIVE_SERVER)` and the UI
     * surfaces @Ores's "Pick a server or enter again" copy.
     */
    private inline fun withActiveServerOrEmit(block: (ServerInfo) -> Unit) {
        val server = activeServer.current.value
        if (server == null) {
            errorFlow.tryEmit(ConnectError.NO_ACTIVE_SERVER)
            return
        }
        block(server)
    }

    /** Cancels in-flight server-bound IO (WS + history polls). */
    private suspend fun cancelServerBoundIo() {
        wsJob?.cancelAndJoin()
        wsJob = null
        val pollSnapshot = pollJobs.toList()
        pollJobs.clear()
        pollSnapshot.forEach { runCatching { it.cancelAndJoin() } }
    }

    private fun openWs(server: ServerInfo, clientId: String) {
        wsJob?.cancel()
        val ws = webSocketSourceFactory(server)
        wsJob = scope.launch {
            ws.connect(clientId)
                .onEach { event ->
                    producedChannel.send(ConnectionInput.Ws(event = event))
                }
                .catch {
                    producedChannel.send(
                        ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE)
                    )
                }
                .collect { /* drained via onEach above */ }
        }
    }

    private fun scheduleTimer(tick: TimerTick, millis: Long) {
        timerJobs[tick]?.cancel()
        timerJobs[tick] = scope.launch {
            delay(millis)
            producedChannel.send(ConnectionInput.Timer(tick))
            timerJobs.remove(tick)
        }
    }

    private fun cancelTimer(tick: TimerTick) {
        timerJobs[tick]?.cancel()
        timerJobs.remove(tick)
    }

    private fun pollHistory(server: ServerInfo, promptId: String) {
        val http = httpClientFactory(server)
        val job = scope.launch {
            val result = try {
                val entry = http.getHistoryEntry(promptId)
                when {
                    entry == null -> HistoryProbeResult.Error(ConnectError.UNKNOWN)
                    entry.status?.completed == true -> HistoryProbeResult.Completed
                    else -> HistoryProbeResult.Running
                }
            } catch (httpEx: ComfyHttpException) {
                val classifiedError = when (httpEx) {
                    is ComfyHttpException.HttpStatus -> when (httpEx.statusCode) {
                        404 -> ConnectError.WRONG_PORT_404
                        else -> ConnectError.UNKNOWN
                    }
                    is ComfyHttpException.MalformedResponse -> ConnectError.NOT_COMFYUI
                    is ComfyHttpException.MissingField -> ConnectError.NOT_COMFYUI
                }
                HistoryProbeResult.Error(classifiedError)
            } catch (t: Throwable) {
                HistoryProbeResult.Error(ConnectError.UNKNOWN)
            }
            producedChannel.send(
                ConnectionInput.HistoryProbe(promptId = promptId, result = result)
            )
        }
        pollJobs.add(job)
        job.invokeOnCompletion { pollJobs.remove(job) }
    }

    // Suppress unused-import warning for `consumeAsFlow` (kept for future
    // alternate consumption path with explicit channel close semantics).
    @Suppress("unused")
    private fun unusedConsumeAsFlow(): Flow<ConnectionInput> = producedChannel.consumeAsFlow()
}
