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
 * `POST /prompt` — workflow submission to a *specific* baseUrl.
 *
 * The `baseUrl` parameter is required (not derived from a global active
 * server holder) per @Lily PR #31 review msg `8bbd4fa1` blocker 1:
 * one run is bound to one server, and a mid-run active-server switch
 * must NOT redirect cancel/WS calls to a different server. The
 * coordinator snapshots the baseUrl from `RunSubmission` at submit
 * time and passes it through every port call for that run.
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
    suspend fun submit(baseUrl: String, request: PromptRequestDto): PromptResponseDto
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
 *
 * `baseUrl` is passed per call so the coordinator can route the cancel
 * to the same server the run was submitted on, even if the user has
 * since switched the active server (per @Lily PR #31 msg `8bbd4fa1`).
 */
interface CancelPort {

    /** Cancel the currently-running prompt on [baseUrl]. */
    suspend fun interruptRunning(baseUrl: String, promptId: String)

    /** Remove a prompt that is queued but not yet running on [baseUrl]. */
    suspend fun deleteQueued(baseUrl: String, promptId: String)
}

/**
 * Stream of WebSocket events for [baseUrl].
 *
 * The Flow is cold (collecting it opens a fresh session); the
 * coordinator collects it exactly once per `run` invocation. Cancelling
 * the coordinator's coroutine cancels the WS subscription.
 *
 * `baseUrl` is parameterized so the WS subscription stays bound to the
 * server the run was submitted on, regardless of subsequent active-server
 * switches.
 *
 * IO exceptions thrown during collection are caught by the coordinator
 * and mapped to [RunState.Failed] with [RunError.Network] when no
 * terminal event has been observed; otherwise the prior terminal stays
 * (we don't downgrade a `Succeeded` into a `Failed` because the socket
 * closed normally).
 */
fun interface WsEventPort {
    fun events(baseUrl: String, clientId: String): Flow<WsEvent>
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
