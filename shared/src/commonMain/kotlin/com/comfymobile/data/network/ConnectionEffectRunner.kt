package com.comfymobile.data.network

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.CancellationException
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
 * ### Active-server awareness (T1.4b part 3d-ii, @Lily PR #18 / #19 thread)
 *
 * The runner does NOT capture a single `ComfyHttpClient` /
 * `WebSocketSource` at construction. Instead it takes:
 *  - [activeServer]: [ActiveServerHolder] — the source of truth for
 *    which server the user is currently connected to.
 *  - [httpClientFactory] / [webSocketSourceFactory]: per-server
 *    factories invoked at effect-run time so each effect uses the
 *    *current* active server's baseUrl, not a stale one.
 *
 * Three contract invariants come from this design:
 *
 *  1. **No active server → explicit `Lost(NO_ACTIVE_SERVER)`, no IO.**
 *     Any side effect that needs a server (`OpenWs`, `PollHistory`,
 *     `PollActiveHistory`) while [ActiveServerHolder.current] is
 *     `null` performs **zero IO** and:
 *     a) emits [ConnectError.NO_ACTIVE_SERVER] on [emittedErrors]
 *        (replay = 1 so a late UI subscriber still sees it), AND
 *     b) sends [ConnectionInput.ConnectAttempt] with that error onto
 *        [producedInputs] so the state machine reducer transitions
 *        to `Lost(NO_ACTIVE_SERVER)` and the UI surfaces @Ores's
 *        "Pick a server" copy. Per @Lily PR #19 review comment
 *        `4413957569` blocker 3: emitting on `errors` alone leaves
 *        the state machine stuck in `Reconnecting` with no visible
 *        error panel.
 *
 *  2. **Active-server change cancels in-flight server-bound IO,
 *     synchronously, before any new IO starts.** Each `run(intent)`
 *     entry-point snapshots [ActiveServerHolder.current] and, if the
 *     observed server id differs from the previously-observed one,
 *     synchronously cancels the WS job and any in-flight history
 *     polls *before* launching new IO. There is **no async observer
 *     coroutine** — Lily PR #19 review comment `4413957569` blocker
 *     4 specifically called out the race where an async observer
 *     could cancel the *new* server's job. The synchronous-sync
 *     approach mirrors Lily's recommendation: "synchronously update
 *     the runner's observed server before starting new IO."
 *
 *  3. **Cancellation propagates.** History-poll coroutines treat
 *     [CancellationException] as structured-concurrency cancellation
 *     and rethrow it. They do NOT classify it as
 *     `HistoryProbeResult.Error(UNKNOWN)` (which would emit a stale
 *     probe result for a server we no longer care about). Same
 *     pattern as [com.comfymobile.data.connect.ConnectAttemptCoordinator]
 *     (PR #18 thread `75c88c17`).
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

    /**
     * Server id we last synced with. Updated by [syncActiveServer]
     * which runs synchronously inside [run] before any new IO is
     * launched, so a `setActive(B)` followed by an immediate
     * `run(OpenWs)` cannot race against an async observer.
     */
    private var lastObservedServerId: String? = activeServer.current.value?.serverId

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
        // Synchronously detect active-server change and cancel any
        // server-bound IO from the previous server BEFORE we launch
        // new IO. No async observer coroutine — see class KDoc
        // invariant 2.
        val currentServer = activeServer.current.value
        syncActiveServer(currentServer)

        when (intent) {
            is SideEffectIntent.OpenWs -> {
                if (currentServer == null) dispatchNoActiveServer()
                else openWs(currentServer, intent.clientId)
            }
            is SideEffectIntent.PollHistory -> {
                if (currentServer == null) dispatchNoActiveServer()
                else pollHistory(currentServer, intent.promptId)
            }
            SideEffectIntent.PollActiveHistory -> {
                if (currentServer == null) dispatchNoActiveServer()
                else activePromptIds.toList().forEach { pollHistory(currentServer, it) }
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
     * If the active server's id has changed since the previous
     * `run()` invocation, synchronously cancel the WS session + any
     * in-flight history polls before any new IO is launched.
     *
     * Called *before* dispatching the current intent's branch, so the
     * new server's launches happen against fresh slot state.
     */
    private fun syncActiveServer(currentServer: ServerInfo?) {
        val newId = currentServer?.serverId
        if (newId != lastObservedServerId) {
            wsJob?.cancel()
            wsJob = null
            val pollSnapshot = pollJobs.toList()
            pollJobs.clear()
            pollSnapshot.forEach { it.cancel() }
            lastObservedServerId = newId
        }
    }

    /**
     * Emit the user-facing `NO_ACTIVE_SERVER` error AND dispatch a
     * `ConnectAttempt(NO_ACTIVE_SERVER)` so the reducer transitions
     * to `Lost(NO_ACTIVE_SERVER)` (per @Lily PR #19 review blocker 3
     * comment `4413957569`).
     */
    private fun dispatchNoActiveServer() {
        errorFlow.tryEmit(ConnectError.NO_ACTIVE_SERVER)
        producedChannel.trySend(
            ConnectionInput.ConnectAttempt(classified = ConnectError.NO_ACTIVE_SERVER),
        )
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
            } catch (ce: CancellationException) {
                // Per @Lily PR #19 review blocker 4 (`4413957569`):
                // structured-concurrency cancellation must propagate.
                // A switch- or shutdown-driven cancel is NOT a
                // probe failure — emitting `Error(UNKNOWN)` here would
                // leak a stale `HistoryProbe` for a server we no
                // longer care about.
                throw ce
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
