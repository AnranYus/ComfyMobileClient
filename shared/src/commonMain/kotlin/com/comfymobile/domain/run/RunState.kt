package com.comfymobile.domain.run

import com.comfymobile.domain.job.JobOutputRef
import kotlinx.serialization.json.JsonElement

/**
 * State transitions for a single workflow run.
 *
 * Lifecycle:
 *   Idle → Submitting → Queued → Running → (Succeeded | Failed | Cancelled)
 * or
 *   Idle → Submitting → Failed                  (rejected at submit time)
 *
 * `Queued` is observed when the server's `Status` event arrives before
 * the first `Executing` for this prompt; on healthy A-branch connections
 * the prompt usually transitions directly Submitting → Running (the
 * queue is empty so the server starts executing in the same network
 * roundtrip).
 *
 * Terminal states (Succeeded / Failed / Cancelled) are exactly the
 * states no further transition can leave. Mirrors `JobStatus.isTerminal`
 * in the persistence layer.
 *
 * The state is intentionally a sealed interface (not enum) so each
 * variant carries the data the UI layer needs without an out-of-band
 * lookup: cached/completed node sets for `RenderPlan` highlighting,
 * progress for the step bar, etc.
 *
 * Wire-event mapping is documented in `RunCoordinator`.
 */
sealed interface RunState {

    /** No active run yet, or the coordinator was reset. */
    data object Idle : RunState

    /**
     * `POST /prompt` is in flight. UI shows a non-cancellable "提交中…"
     * indicator; no `promptId` exists yet.
     */
    data object Submitting : RunState

    /**
     * Prompt was accepted but the server has not yet emitted an
     * `executing` event for it. Cancel routes to `POST /queue` body
     * `{"delete":[promptId]}` (per T0.4 §3.2 + @Lily hard verification
     * point #26).
     *
     * [queuePosition] is the `number` field from `PromptResponseDto`,
     * which is the queue counter at submission time (not the live
     * position; ComfyUI does not expose live position).
     */
    data class Queued(
        val promptId: String,
        val queuePosition: Int,
    ) : RunState

    /**
     * The server is processing this prompt. Cancel routes to
     * `POST /interrupt` (per T0.4 §3.2 + @Lily hard verification
     * point #26).
     *
     * [currentNodeId] / [currentNodeDisplayName] reflect the most
     * recent `executing` event with a non-null node. A null
     * `currentNodeId` means the server has finished all nodes and is
     * about to emit `execution_success` (the sentinel `executing(node=null)`
     * frame defined in T0.1 §2).
     *
     * [cachedNodes] / [completedNodes] are accumulated from
     * `execution_cached` and `executed` events respectively, so the
     * graph render layer can show ⚡ and ✓ marks per T0.4 §3.2.
     *
     * [nodeProgress] is the most recent `progress` event, used to drive
     * the in-node `value/max` bar in the progress detail card.
     */
    data class Running(
        val promptId: String,
        val currentNodeId: String? = null,
        val currentNodeDisplayName: String? = null,
        val cachedNodes: Set<String> = emptySet(),
        val completedNodes: Set<String> = emptySet(),
        val nodeProgress: NodeProgress? = null,
        val firstOutput: JobOutputRef? = null,
    ) : RunState

    /**
     * `execution_success` received; outputs collected from `executed`
     * events (image triples extracted into [outputs]).
     *
     * [outputs] preserves insertion order of `executed` events that
     * carried image-typed payloads, so the gallery (#27) shows them
     * in the natural execution order.
     */
    data class Succeeded(
        val promptId: String,
        val outputs: List<JobOutputRef>,
    ) : RunState

    /**
     * Terminal failure. `promptId` is null only when the failure
     * happened at submit time (validation errors / network error
     * before the prompt was accepted).
     */
    data class Failed(
        val promptId: String?,
        val error: RunError,
    ) : RunState

    /**
     * The user requested cancel and the server confirmed via
     * `execution_interrupted`. `fromNode` is the node the server was
     * processing when the interrupt landed, when known.
     */
    data class Cancelled(
        val promptId: String,
        val fromNodeId: String? = null,
    ) : RunState

    /** Per-node progress, derived from the `progress` WS event. */
    data class NodeProgress(
        val nodeId: String,
        val value: Int,
        val max: Int,
    )
}

/**
 * Why a run reached [RunState.Failed].
 *
 * Wire shape is preserved so the UI layer can show a meaningful sheet
 * (per T2.7 §3.4 — failure sheet renders node displayName + collapsed
 * traceback + "再试一次" CTA).
 */
sealed interface RunError {

    /**
     * `POST /prompt` returned a 200 with a non-empty `node_errors`
     * map (ComfyUI's pre-execution validation rejected some nodes).
     */
    data class ValidationFailed(
        val nodeErrors: Map<String, JsonElement>,
    ) : RunError

    /**
     * `execution_error` received: a node threw during execution.
     * `traceback` / `currentInputs` / `currentOutputs` are kept as
     * raw `JsonElement` because the payload shape varies across
     * ComfyUI versions and custom nodes (see WsEvent.ExecutionError).
     */
    data class NodeException(
        val nodeId: String,
        val nodeType: String,
        val exceptionMessage: String,
        val exceptionType: String,
        val traceback: JsonElement? = null,
    ) : RunError

    /**
     * Network / IO failure during submission or while listening for
     * WS events. The original Throwable is preserved for diagnostic
     * surfaces (and `CancellationException` is NOT mapped here — it
     * propagates so callers can distinguish "the user navigated away"
     * from "the network died").
     */
    data class Network(
        val cause: Throwable,
    ) : RunError

    /**
     * The server emitted `execution_success` but the prompt had no
     * image-typed outputs at all (rare; usually means the workflow
     * lacked a SaveImage / PreviewImage terminal node). We surface
     * this as a recoverable failure so the user knows nothing reached
     * the gallery rather than silently transitioning to an empty
     * Succeeded.
     */
    data object NoOutputs : RunError
}
