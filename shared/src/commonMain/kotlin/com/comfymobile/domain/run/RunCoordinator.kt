package com.comfymobile.domain.run

import com.comfymobile.data.network.WsEvent
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.job.JobStatus
import com.comfymobile.domain.workflow.WorkflowConverter
import com.comfymobile.domain.workflow.WorkflowGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Single-source-of-truth orchestrator for one workflow run.
 *
 * **Responsibilities** (kept narrow on purpose):
 *  - Convert a UI workflow into the API form via [WorkflowConverter].
 *  - Submit it through [PromptSubmissionPort].
 *  - Subscribe to [WsEventPort] events, filter by `promptId`, drive a
 *    pure state machine into [RunState].
 *  - Persist the resulting [Job] to [JobRepository] at submission and
 *    at terminal.
 *  - Route a cancel request to the right network endpoint based on the
 *    *current* state ([RunState.Running] → `/interrupt`,
 *    [RunState.Queued] → `/queue {delete:[id]}`).
 *
 * **NOT in scope here**:
 *  - WebSocket reconnection / B-branch polling — those live in
 *    [com.comfymobile.data.network.ConnectionEffectRunner].
 *  - History gallery rendering — owned by T2.4 (#27).
 *  - Background-resumed (`C`) banner — surfaced by the view layer based
 *    on the `RunState` + `ConnectionState` combination.
 *
 * **Concurrency model**:
 *  Only one run is permitted in flight at a time. Calling [run] while
 *  another run is still non-terminal returns immediately without
 *  side-effects (state stays on the existing run). Tests cover this.
 *
 * **Cancellation**:
 *  Cancelling the coroutine that called [run] is *separate* from
 *  asking the server to cancel the prompt. To stop the server-side
 *  work, call [requestCancel] — it will route to the right endpoint
 *  based on current state. Cancelling the coroutine alone leaves the
 *  server still running.
 *
 *  When [run]'s coroutine receives `CancellationException` (e.g. the
 *  caller's scope was cancelled), state is left at whatever terminal
 *  was last emitted; if no terminal had been emitted, state is reset
 *  to [RunState.Idle]. This is intentional — cancelling the *scope* is
 *  a "navigate away" signal, not a "kill the run" signal.
 *
 * **Test seams**:
 *  All four IO dependencies are interfaces ([PromptSubmissionPort],
 *  [CancelPort], [WsEventPort], [Clock]) so [RunCoordinatorTest] can
 *  drive every branch with fakes; no Ktor MockEngine needed.
 */
class RunCoordinator(
    private val prompt: PromptSubmissionPort,
    private val cancel: CancelPort,
    private val ws: WsEventPort,
    private val converter: WorkflowConverter,
    private val jobs: JobRepository,
    private val clock: Clock,
) {

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    /**
     * Mutex guarding the "one run at a time" invariant. Held for the
     * entire lifetime of [run], so a second concurrent [run] call
     * returns immediately via [Mutex.tryLock] failure.
     */
    private val runMutex = Mutex()

    /**
     * Reference to the current run's local-cancel pathway. Published
     * BEFORE [_state] transitions to [RunState.Queued] so external
     * callers that observe Queued and immediately call [requestCancel]
     * can never see a `null` cancel channel (per @Lily PR #30 review
     * msg `42ee1862`).
     *
     * A [Channel] (rather than a SharedFlow) carries the signal because
     * Channel semantics decouple send from receive — the buffered value
     * sits in the channel regardless of whether the reducer's collector
     * coroutine has subscribed yet. SharedFlow with `replay = 0` would
     * drop the signal if no subscriber is active at emit time, which is
     * exactly the race condition we need to avoid.
     *
     * The reference is cleared in [executeRun]'s `finally` so a stale
     * channel from a previous run cannot be reused.
     */
    private val activeCancelChannel = MutableStateFlow<ActiveCancelChannel?>(null)

    /**
     * Runs a single workflow end-to-end.
     *
     * Suspends until the run reaches a terminal state ([RunState.Succeeded],
     * [RunState.Failed], or [RunState.Cancelled]). Returns the terminal
     * state for convenience.
     *
     * If another run is already in flight, returns the current state
     * without touching anything (no side-effects, no exceptions).
     *
     * State transitions are published on [state] as they happen so the
     * UI can observe progress reactively.
     */
    suspend fun run(submission: RunSubmission): RunState {
        if (!runMutex.tryLock()) {
            // Another run is in flight; reject without disturbing it.
            return _state.value
        }
        try {
            return executeRun(submission)
        } finally {
            runMutex.unlock()
        }
    }

    /**
     * Request server-side cancellation of the in-flight run.
     *
     *  - [RunState.Running] → calls [CancelPort.interruptRunning]. The
     *    server is expected to emit `execution_interrupted` over WS,
     *    which drives the state transition to [RunState.Cancelled] via
     *    the normal reducer path. No local finalize.
     *  - [RunState.Queued]  → calls [CancelPort.deleteQueued] AND
     *    locally finalizes the run. ComfyUI does NOT emit
     *    `execution_interrupted` for a prompt that was deleted from the
     *    queue before executing (the prompt never ran, so there is
     *    nothing to interrupt). The local finalize is delivered through
     *    [forceTerminalSignal] so the reducer path is unchanged and the
     *    WS collector terminates cleanly. (Per @Lily PR #30 review,
     *    msg `49b81084`.)
     *  - Any other state    → no-op (idle / submitting / terminal).
     *
     * Returns the route taken so callers / tests can verify the
     * separation; null when the request was a no-op.
     */
    suspend fun requestCancel(): CancelRoute? {
        return when (val current = _state.value) {
            is RunState.Running -> {
                cancel.interruptRunning(current.promptId)
                CancelRoute.InterruptRunning(current.promptId)
            }
            is RunState.Queued -> {
                cancel.deleteQueued(current.promptId)
                // Locally finalize since the server will not emit an
                // execution_interrupted event for queue-deletes. The
                // cancel channel was published before state = Queued so
                // it is guaranteed visible by the time we get here (per
                // @Lily PR #30 race fix, msg `42ee1862`).
                val slot = activeCancelChannel.value
                if (slot != null && slot.promptId == current.promptId) {
                    // trySend never blocks: Channel.capacity = 1 means
                    // the value sits in the buffer until the reducer's
                    // cancel collector reads it. Subscription order is
                    // irrelevant — that's why we use Channel, not
                    // SharedFlow.
                    slot.channel.trySend(Terminal.Cancelled(fromNodeId = null))
                }
                CancelRoute.DeleteQueued(current.promptId)
            }
            else -> null
        }
    }

    // ----------------------------------------------------------------- internals

    private suspend fun executeRun(submission: RunSubmission): RunState {
        _state.value = RunState.Submitting

        // 1. UI → API conversion. Throws IllegalArgumentException if the
        //    workflow is malformed; we surface that as a Failed terminal
        //    so the UI can show a non-crashing error sheet.
        val apiGraph = try {
            converter.uiToApi(submission.workflowUi, submission.objectInfo)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return finalizeFailed(promptId = null, error = RunError.Network(t), persistJob = false)
        }

        // 2. POST /prompt
        val request = PromptRequestDto(
            prompt = apiGraph.toJsonElement(),
            client_id = submission.clientId,
            extra_data = submission.extraData,
        )
        val response = try {
            prompt.submit(request)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return finalizeFailed(promptId = null, error = RunError.Network(t), persistJob = false)
        }

        if (response.node_errors.isNotEmpty()) {
            return finalizeFailed(
                promptId = response.prompt_id,
                error = RunError.ValidationFailed(response.node_errors),
                persistJob = false,
            )
        }

        // 3. Set up the local-cancel channel BEFORE publishing
        //    state = Queued. An external observer that sees Queued and
        //    immediately calls requestCancel MUST be able to find a
        //    working channel — otherwise the cancel signal is lost. Per
        //    @Lily PR #30 race blocker, msg `42ee1862`.
        val cancelChannel = Channel<Terminal>(capacity = 1)
        activeCancelChannel.value = ActiveCancelChannel(
            promptId = response.prompt_id,
            channel = cancelChannel,
        )

        try {
            // 4. Persist the queued row before listening to WS so a crash
            //    between submit and event arrival still leaves a recoverable
            //    history entry.
            val createdAt = clock.nowEpochMs()
            jobs.upsert(
                Job(
                    promptId = response.prompt_id,
                    serverId = submission.serverId,
                    status = JobStatus.QUEUED,
                    workflowSnapshotJson = submission.workflowSnapshotJson,
                    apiPromptJson = null, // optional; large; skipped for now
                    label = submission.label,
                    firstOutput = null,
                    createdAtEpochMs = createdAt,
                    finishedAtEpochMs = null,
                )
            )

            _state.value = RunState.Queued(
                promptId = response.prompt_id,
                queuePosition = response.number,
            )

            // 5. Drive the WS state machine.
            return try {
                driveWsLifecycle(
                    promptId = response.prompt_id,
                    clientId = submission.clientId,
                    serverId = submission.serverId,
                    cancelChannel = cancelChannel,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    // Scope cancelled: leave state where it is unless we
                    // never reached a terminal — in that case reset to Idle
                    // so we don't leave a stuck Submitting/Queued/Running.
                    if (!_state.value.isTerminal()) {
                        _state.value = RunState.Idle
                    }
                    throw t
                }
                finalizeFailed(
                    promptId = response.prompt_id,
                    error = RunError.Network(t),
                    persistJob = true,
                )
            }
        } finally {
            // Always clear the active cancel ref and close the channel so
            // a stale channel from this run cannot be used by a future
            // requestCancel call. Cleared AFTER driveWsLifecycle returns,
            // by which point state is terminal and requestCancel would be
            // a no-op anyway.
            activeCancelChannel.value = null
            cancelChannel.close()
        }
    }

    /**
     * Subscribe to WS events for [promptId] and drive the state
     * machine. Returns the terminal [RunState].
     *
     * **Termination contract** (per @Lily PR #30 review msgs `49b81084` /
     * `e1d26e71`):
     *
     * The reducer loop MUST exit deterministically across THREE distinct
     * conditions, each independent of the long-lived WebSocket socket:
     *
     *   1. Server-driven terminal — `execution_success` / `execution_error`
     *      / `execution_interrupted` arrives; reducer commits `Terminal`;
     *      loop breaks.
     *   2. Local force-terminal — [requestCancel] for the Queued state
     *      emits a synthesised `Terminal.Cancelled` because ComfyUI does
     *      NOT send `execution_interrupted` for queue-deleted prompts.
     *   3. WS stream completed without terminal — server closed the
     *      socket politely or it dropped; we surface this as
     *      [RunError.Network] so the UI doesn't hang.
     *
     * Implementation uses an explicit `Channel<MachineInput>` fan-in
     * instead of `Flow.merge`. The merge approach is unsafe here because
     * [forceTerminalSignal] is a [MutableSharedFlow] that never completes,
     * which would prevent merge from completing when the WS flow ends —
     * defeating termination condition (3). With a Channel, the WS
     * collector explicitly sends [MachineInput.WsCompleted] /
     * [MachineInput.WsError] when its Flow ends, and the reducer breaks
     * cleanly.
     *
     * Wire-event mapping (T0.1 §2 + T0.4 §3.2):
     *  - `execution_start(promptId)`       → transition Queued → Running (empty bookkeeping)
     *  - `execution_cached(promptId, nodes)` → add to cachedNodes
     *  - `executing(promptId, node)`       → set currentNodeId; node=null = end-of-run sentinel
     *  - `progress(promptId, node, v, max)`→ set nodeProgress
     *  - `executed(promptId, node, output)`→ add to completedNodes; extract first image into firstOutput
     *  - `execution_success(promptId)`     → terminal Succeeded
     *  - `execution_error(promptId, …)`    → terminal Failed(NodeException)
     *  - `execution_interrupted(promptId, …)` → terminal Cancelled
     *  - synthesised ForceTerminal(Cancelled) → terminal Cancelled (queued-cancel)
     *
     * Events for *other* prompt ids are ignored — multiple clients can
     * share a server, and the WS multiplexes.
     */
    private suspend fun driveWsLifecycle(
        promptId: String,
        clientId: String,
        serverId: String,
        cancelChannel: Channel<Terminal>,
    ): RunState = coroutineScope {
        val inputs = Channel<MachineInput>(capacity = Channel.UNLIMITED)

        val wsJob = launch {
            try {
                ws.events(clientId).collect { event ->
                    inputs.trySend(MachineInput.WsFrame(event))
                }
                // Flow completed without an error — server closed the socket.
                inputs.trySend(MachineInput.WsCompleted)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                inputs.trySend(MachineInput.WsError(t))
            }
        }

        // Drain the per-run cancel channel into the reducer's input
        // stream. The channel exists BEFORE state = Queued (see
        // executeRun), so any signal sent by requestCancel between
        // Queued-publication and this coroutine starting its for-loop
        // sits in the channel's 1-slot buffer and is read immediately
        // when forceJob enters its iteration. There is no subscribe-gap
        // race because Channel decouples send from receive.
        val forceJob = launch {
            for (terminal in cancelChannel) {
                inputs.trySend(MachineInput.Force(terminal))
            }
        }

        var snapshot = MachineSnapshot(promptId = promptId)
        var wsError: Throwable? = null

        try {
            for (input in inputs) {
                when (input) {
                    is MachineInput.WsFrame -> {
                        val next = MachineStep.step(snapshot, input.event) ?: continue
                        snapshot = next
                    }
                    is MachineInput.Force -> {
                        snapshot = snapshot.copy(terminal = input.terminal)
                    }
                    is MachineInput.WsCompleted -> {
                        // Server closed /ws politely without a terminal event.
                        // Fall through to the post-loop "no terminal" branch
                        // which finalizes as Failed(Network).
                        if (snapshot.terminal == null) break
                        continue
                    }
                    is MachineInput.WsError -> {
                        wsError = input.cause
                        if (snapshot.terminal == null) break
                        continue
                    }
                }
                if (snapshot.terminal != null) {
                    finalizeFromSnapshot(snapshot)
                    break
                } else {
                    applySnapshot(snapshot)
                }
            }
        } finally {
            // Cancel both fan-in jobs before returning so they don't
            // keep collecting after we've decided on a terminal.
            wsJob.cancel()
            forceJob.cancel()
            inputs.close()
        }

        if (snapshot.terminal == null) {
            return@coroutineScope finalizeFailed(
                promptId = promptId,
                error = RunError.Network(
                    wsError ?: IllegalStateException("WS closed before terminal event")
                ),
                persistJob = true,
            )
        }
        _state.value
    }

    private fun applySnapshot(snapshot: MachineSnapshot) {
        if (snapshot.terminal != null) return // terminal handled by finalizeFromSnapshot
        _state.value = RunState.Running(
            promptId = snapshot.promptId,
            currentNodeId = snapshot.currentNodeId,
            currentNodeDisplayName = snapshot.currentNodeDisplayName,
            cachedNodes = snapshot.cachedNodes,
            completedNodes = snapshot.completedNodes,
            nodeProgress = snapshot.nodeProgress,
            firstOutput = snapshot.firstOutput,
        )
    }

    private suspend fun finalizeFromSnapshot(snapshot: MachineSnapshot) {
        val terminal = snapshot.terminal ?: return
        val now = clock.nowEpochMs()
        val promptId = snapshot.promptId
        when (terminal) {
            is Terminal.Success -> {
                val outputs = snapshot.outputs
                if (outputs.isEmpty()) {
                    finalizeFailed(promptId, RunError.NoOutputs, persistJob = true)
                    return
                }
                jobs.updateStatus(promptId, JobStatus.SUCCEEDED, now)
                jobs.updateFirstOutput(promptId, outputs.first())
                _state.value = RunState.Succeeded(promptId, outputs)
            }
            is Terminal.Failure -> {
                jobs.updateStatus(promptId, JobStatus.FAILED, now)
                _state.value = RunState.Failed(promptId, terminal.error)
            }
            is Terminal.Cancelled -> {
                jobs.updateStatus(promptId, JobStatus.INTERRUPTED, now)
                _state.value = RunState.Cancelled(promptId, terminal.fromNodeId)
            }
        }
    }

    private suspend fun finalizeFailed(
        promptId: String?,
        error: RunError,
        persistJob: Boolean,
    ): RunState {
        if (persistJob && promptId != null) {
            jobs.updateStatus(promptId, JobStatus.FAILED, clock.nowEpochMs())
        }
        val terminal = RunState.Failed(promptId, error)
        _state.value = terminal
        return terminal
    }
}

/**
 * The information [RunCoordinator.run] needs to launch one workflow.
 *
 *  - [serverId]: the active server identifier for [JobRepository] rows.
 *  - [clientId]: the WS client id; included in `PromptRequestDto.client_id`
 *    so the server stamps emitted events with the same id our WS reads.
 *  - [workflowUi]: the UI-form workflow snapshot (typically the user's
 *    current edited graph). Converted to API form internally.
 *  - [objectInfo]: optional `/object_info` cache to help the converter
 *    resolve widget order for unknown classTypes.
 *  - [workflowSnapshotJson]: the **current edited** UI JSON the caller
 *    wants to persist in [Job.workflowSnapshotJson] for "reopen
 *    workflow" history. Per @Lily T0.6 bug catch: must be the *current*
 *    UI snapshot, not the originally-imported one (ADR-0003).
 *  - [label]: optional user-visible label.
 *  - [extraData]: optional `extra_data` to embed in the PNG (e.g.
 *    `{"extra_pnginfo": {"workflow": <ui snapshot>}}`). Caller is
 *    responsible for shape.
 */
data class RunSubmission(
    val serverId: String,
    val clientId: String,
    val workflowUi: WorkflowGraph.Ui,
    val objectInfo: JsonElement? = null,
    val workflowSnapshotJson: String? = null,
    val label: String? = null,
    val extraData: JsonElement? = null,
)

/** Which network endpoint a cancel was routed to. */
sealed interface CancelRoute {
    data class InterruptRunning(val promptId: String) : CancelRoute
    data class DeleteQueued(val promptId: String) : CancelRoute
}

// -------------------------------------------------------------------- pure state machine

/**
 * Pure-data snapshot of the in-progress run, accumulated as WS events
 * arrive. The state machine logic in [MachineStep.step] is pure (no
 * suspension, no IO) so it can be unit-tested directly without spinning
 * up the coordinator.
 */
internal data class MachineSnapshot(
    val promptId: String,
    val currentNodeId: String? = null,
    val currentNodeDisplayName: String? = null,
    val cachedNodes: Set<String> = emptySet(),
    val completedNodes: Set<String> = emptySet(),
    val nodeProgress: RunState.NodeProgress? = null,
    val outputs: List<JobOutputRef> = emptyList(),
    val firstOutput: JobOutputRef? = null,
    val terminal: Terminal? = null,
)

internal sealed interface Terminal {
    data object Success : Terminal
    data class Failure(val error: RunError) : Terminal
    data class Cancelled(val fromNodeId: String?) : Terminal
}

/**
 * Unified input type for the reducer loop in [RunCoordinator.driveWsLifecycle].
 *
 * Four sources feed into the same reducer: real WS frames, synthesised
 * "force terminal" signals from [RunCoordinator.requestCancel], and two
 * structural events for the WS Flow's completion (clean close vs error).
 *
 * Encoding the WS lifecycle as explicit inputs keeps the reducer
 * decision site in one `for (input in inputs)` loop and lets us break
 * deterministically across all three termination conditions documented
 * on [RunCoordinator.driveWsLifecycle].
 */
internal sealed interface MachineInput {
    data class WsFrame(val event: WsEvent) : MachineInput
    data class Force(val terminal: Terminal) : MachineInput
    data object WsCompleted : MachineInput
    data class WsError(val cause: Throwable) : MachineInput
}

/**
 * Carrier for [RunCoordinator.activeCancelChannel]. The `promptId` tag
 * is checked by [RunCoordinator.requestCancel] before sending so a
 * cleanup race (channel cleared mid-cancel) cannot deliver a signal
 * meant for a prior run.
 */
internal data class ActiveCancelChannel(
    val promptId: String,
    val channel: Channel<Terminal>,
)

/**
 * Pure state-machine reducer. Returns null when the event is for a
 * different prompt or unrecognised; never throws.
 */
internal object MachineStep {

    fun step(snapshot: MachineSnapshot, event: WsEvent): MachineSnapshot? {
        // Filter out events for other prompts; control events with no
        // prompt id (Status / FeatureFlags / Unknown) pass through but
        // produce no state change.
        when (event) {
            is WsEvent.Status -> return null
            is WsEvent.FeatureFlags -> return null
            is WsEvent.Unknown -> return null
            else -> Unit
        }
        val eventPromptId = event.promptIdOrNull() ?: return null
        if (eventPromptId != snapshot.promptId) return null

        return when (event) {
            is WsEvent.ExecutionStart -> snapshot.copy(
                // First sign of execution; nothing else to record here.
                currentNodeId = null,
                currentNodeDisplayName = null,
            )
            is WsEvent.ExecutionCached -> snapshot.copy(
                cachedNodes = snapshot.cachedNodes + event.nodes,
            )
            is WsEvent.Executing -> snapshot.copy(
                currentNodeId = event.node,
                currentNodeDisplayName = event.displayNode,
            )
            is WsEvent.Progress -> snapshot.copy(
                nodeProgress = RunState.NodeProgress(
                    nodeId = event.node,
                    value = event.value,
                    max = event.max,
                ),
            )
            is WsEvent.ProgressState -> snapshot // aggregate snapshot; we don't repaint
            is WsEvent.Executed -> {
                val images = extractImageOutputs(event.output)
                val newFirst = snapshot.firstOutput ?: images.firstOrNull()
                snapshot.copy(
                    completedNodes = snapshot.completedNodes + event.node,
                    nodeProgress = null, // node finished; clear in-node progress
                    outputs = snapshot.outputs + images,
                    firstOutput = newFirst,
                )
            }
            is WsEvent.ExecutionSuccess -> snapshot.copy(
                terminal = Terminal.Success,
                currentNodeId = null,
                currentNodeDisplayName = null,
            )
            is WsEvent.ExecutionError -> snapshot.copy(
                terminal = Terminal.Failure(
                    RunError.NodeException(
                        nodeId = event.nodeId,
                        nodeType = event.nodeType,
                        exceptionMessage = event.exceptionMessage,
                        exceptionType = event.exceptionType,
                        traceback = event.traceback,
                    )
                ),
            )
            is WsEvent.ExecutionInterrupted -> snapshot.copy(
                terminal = Terminal.Cancelled(fromNodeId = event.nodeId),
            )
            // already handled above:
            is WsEvent.Status, is WsEvent.FeatureFlags, is WsEvent.Unknown -> null
        }
    }

    /**
     * Extract image-shaped outputs from an `Executed.output` payload.
     * ComfyUI shape:
     *
     *   { "images": [ { "filename":…, "subfolder":…, "type":"output" }, … ] }
     *
     * Also accepts the same shape under `"gifs"` (commonly used by
     * AnimateDiff custom nodes) so basic video frames flow through.
     * Other top-level keys are ignored for now.
     */
    fun extractImageOutputs(output: JsonElement): List<JobOutputRef> {
        if (output !is JsonObject) return emptyList()
        val result = mutableListOf<JobOutputRef>()
        val keys = listOf("images", "gifs")
        for (key in keys) {
            val arr = output[key]?.takeIf { it is JsonArray } as? JsonArray ?: continue
            for (item in arr) {
                val ref = (item as? JsonObject)?.toJobOutputRef() ?: continue
                result.add(ref)
            }
        }
        return result
    }

    private fun JsonObject.toJobOutputRef(): JobOutputRef? {
        val filename = this["filename"]?.asString() ?: return null
        val subfolder = this["subfolder"]?.asString().orEmpty()
        val type = this["type"]?.asString() ?: JobOutputRef.TYPE_OUTPUT
        return JobOutputRef(filename = filename, subfolder = subfolder, type = type)
    }

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
}

// -------------------------------------------------------------------- helpers

internal fun WsEvent.promptIdOrNull(): String? = when (this) {
    is WsEvent.ExecutionStart -> promptId
    is WsEvent.ExecutionCached -> promptId
    is WsEvent.Executing -> promptId
    is WsEvent.Progress -> promptId
    is WsEvent.ProgressState -> promptId
    is WsEvent.Executed -> promptId
    is WsEvent.ExecutionError -> promptId
    is WsEvent.ExecutionInterrupted -> promptId
    is WsEvent.ExecutionSuccess -> promptId
    is WsEvent.Status, is WsEvent.FeatureFlags, is WsEvent.Unknown -> null
}

private fun RunState.isTerminal(): Boolean =
    this is RunState.Succeeded || this is RunState.Failed || this is RunState.Cancelled

/**
 * Helper: render an `Api` graph as the JsonElement expected by the
 * `PromptRequestDto.prompt` field. Kept private to this file so the
 * conversion shape is owned in one place.
 */
private fun WorkflowGraph.Api.toJsonElement(): JsonElement {
    val byId = nodes.mapValues { (_, node) ->
        JsonObject(
            mapOf(
                "class_type" to JsonPrimitive(node.classType),
                "inputs" to JsonObject(node.inputs),
            )
        )
    }
    return JsonObject(byId)
}
