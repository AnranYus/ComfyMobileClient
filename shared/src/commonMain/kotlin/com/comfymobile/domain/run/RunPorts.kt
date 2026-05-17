package com.comfymobile.domain.run

import com.comfymobile.data.network.WsEvent
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.data.network.dto.PromptResponseDto
import kotlinx.coroutines.flow.Flow

/**
 * IO seams for [RunCoordinator]. Production code wires these to
 * `ComfyHttpClient` and `ComfyWebSocketClient`; tests substitute fakes
 * so `RunCoordinatorTest` can drive every state-machine branch
 * deterministically without spinning up Ktor.
 *
 * The seams deliberately mirror the network surface 1:1 rather than
 * collapsing into a single "RunGateway" — this matches the @Lily seam
 * principle established in T1.1 (msg `1fa6de6f` / `af119226`): keep
 * orthogonal network actions on orthogonal interfaces so tests can
 * verify the *routing* between them (queued-cancel vs running-cancel).
 */

/**
 * `POST /prompt` — workflow submission. Returns the accepted prompt id
 * and the per-node validation error map (empty on full acceptance).
 *
 * Failure modes:
 *  - Non-2xx HTTP: throw [com.comfymobile.data.network.ComfyHttpException.HttpStatus].
 *  - Malformed JSON: throw [com.comfymobile.data.network.ComfyHttpException.MalformedResponse].
 *  - Network IO: throw the underlying IO exception (propagates).
 * Coordinator maps these to [RunState.Failed] with [RunError.Network]
 * unless the response carried `node_errors`, in which case it maps to
 * [RunError.ValidationFailed] instead.
 */
fun interface PromptSubmissionPort {
    suspend fun submit(request: PromptRequestDto): PromptResponseDto
}

/**
 * Two-method port covering the strictly-separate cancel paths.
 *
 *  - [interruptRunning] → `POST /interrupt` body `{prompt_id}`. Use when
 *    the server is currently executing the prompt.
 *  - [deleteQueued] → `POST /queue` body `{"delete":[prompt_id]}`. Use
 *    when the prompt was accepted but not yet executing (state =
 *    `Queued`).
 *
 * They are separate methods (not a single boolean-flagged call) per
 * @Lily T2.3 verification points (msg `cd56665a`) and the underlying
 * `ComfyHttpClient` separation (T1.1 part 2a, msg `1fa6de6f`).
 */
interface CancelPort {

    /** Cancel the currently-running prompt. */
    suspend fun interruptRunning(promptId: String)

    /** Remove a prompt that is queued but not yet running. */
    suspend fun deleteQueued(promptId: String)
}

/**
 * Stream of WebSocket events for the active server.
 *
 * The Flow is cold (collecting it opens a fresh session); the
 * coordinator collects it exactly once per `run` invocation. Cancelling
 * the coordinator's coroutine cancels the WS subscription.
 *
 * IO exceptions thrown during collection are caught by the coordinator
 * and mapped to [RunState.Failed] with [RunError.Network] when no
 * terminal event has been observed; otherwise the prior terminal stays
 * (we don't downgrade a `Succeeded` into a `Failed` because the socket
 * closed normally).
 */
fun interface WsEventPort {
    fun events(clientId: String): Flow<WsEvent>
}

/**
 * Real wall-clock timestamps in epoch milliseconds. Tests inject a fake
 * to make `Job.createdAtEpochMs` / `finishedAtEpochMs` deterministic.
 *
 * Matches the `nowEpochMs()` expect/actual function shipped in T1.4b
 * (DI module). RunCoordinator deliberately takes a port rather than
 * calling the top-level function so tests don't have to manipulate the
 * platform clock.
 */
fun interface Clock {
    fun nowEpochMs(): Long
}
