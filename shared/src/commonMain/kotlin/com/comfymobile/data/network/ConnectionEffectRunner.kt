package com.comfymobile.data.network

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
 * Tests inject a `CoroutineScope` backed by `TestScope` and drive the
 * runner with virtual time, asserting on the inputs the runner sends
 * back into [producedInputs].
 */
class ConnectionEffectRunner(
    private val http: ComfyHttpClient,
    private val ws: WebSocketSource,
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
            is SideEffectIntent.OpenWs -> openWs(intent.clientId)
            is SideEffectIntent.PollHistory -> pollHistory(intent.promptId)
            SideEffectIntent.PollActiveHistory -> activePromptIds.toList().forEach(::pollHistory)
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
    }

    // ----------------------------------------------------------------- private

    private fun openWs(clientId: String) {
        wsJob?.cancel()
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

    private fun pollHistory(promptId: String) {
        scope.launch {
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
    }

    // Suppress unused-import warning for `consumeAsFlow` (kept for future
    // alternate consumption path with explicit channel close semantics).
    @Suppress("unused")
    private fun unusedConsumeAsFlow(): Flow<ConnectionInput> = producedChannel.consumeAsFlow()
}
