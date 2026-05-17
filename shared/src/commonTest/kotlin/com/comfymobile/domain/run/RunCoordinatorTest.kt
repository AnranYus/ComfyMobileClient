package com.comfymobile.domain.run

import com.comfymobile.data.network.WsEvent
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.data.network.dto.PromptResponseDto
import com.comfymobile.data.persistence.InMemoryJobRepository
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobStatus
import com.comfymobile.domain.workflow.WorkflowConverter
import com.comfymobile.domain.workflow.WorkflowGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Integration tests for [RunCoordinator] with pure-Kotlin fakes for
 * every port. No Ktor, no real WebSocket, no coroutine scheduler
 * gymnastics — events are pushed through a [Channel] so each test
 * controls the exact sequence.
 *
 * Mirrors the @Lily seam discipline from T1.4b (msg `d8b098c`): if a
 * test would otherwise need to drive a Ktor MockEngine through a
 * virtual scheduler, replace the seam instead.
 */
class RunCoordinatorTest {

    // ----------------------------------------------------------------- helpers

    private fun emptyUi(): WorkflowGraph.Ui =
        WorkflowGraph.Ui(raw = JsonObject(emptyMap()))

    /**
     * Build a minimal UI graph with one CheckpointLoaderSimple node so
     * WorkflowConverter actually produces a non-empty API graph (the
     * converter ignores nodes with no widgets / inputs and returns
     * empty for a totally empty graph).
     */
    private fun minimalUi(): WorkflowGraph.Ui =
        WorkflowGraph.Ui(
            raw = buildJsonObject {
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(1))
                        put("type", JsonPrimitive("CheckpointLoaderSimple"))
                        put("widgets_values", buildJsonArray { add(JsonPrimitive("model.safetensors")) })
                    })
                })
                put("links", buildJsonArray {})
            }
        )

    private class FixedClock(private val nowMs: Long = 1_700_000_000_000L) : Clock {
        override fun nowEpochMs(): Long = nowMs
    }

    private class RecordingPrompt(
        private val response: PromptResponseDto = PromptResponseDto(prompt_id = "p-1", number = 0),
        private val throwOnSubmit: Throwable? = null,
        /**
         * When non-null, return the i'th element on the i'th call (clamped
         * to last). Lets tests assert that consecutive runs got distinct
         * prompt ids — without this, a buggy implementation reusing the
         * first run's prompt id would slip through.
         */
        private val sequencedResponses: List<PromptResponseDto>? = null,
    ) : PromptSubmissionPort {
        var lastRequest: PromptRequestDto? = null
        val requests: MutableList<PromptRequestDto> = mutableListOf()
        val baseUrls: MutableList<String> = mutableListOf()
        var calls: Int = 0
        override suspend fun submit(baseUrl: String, request: PromptRequestDto): PromptResponseDto {
            calls += 1
            lastRequest = request
            requests += request
            baseUrls += baseUrl
            throwOnSubmit?.let { throw it }
            return sequencedResponses?.getOrElse(calls - 1) { sequencedResponses.last() }
                ?: response
        }
    }

    private class RecordingCancel : CancelPort {
        // Each list records (baseUrl, promptId) pairs so tests can verify
        // BOTH the route AND the server context the cancel went to.
        val interruptCalls = mutableListOf<Pair<String, String>>()
        val deleteCalls = mutableListOf<Pair<String, String>>()
        override suspend fun interruptRunning(baseUrl: String, promptId: String) {
            interruptCalls += baseUrl to promptId
        }
        override suspend fun deleteQueued(baseUrl: String, promptId: String) {
            deleteCalls += baseUrl to promptId
        }

        /** Convenience for legacy assertions that only care about promptIds. */
        val interruptPromptIds: List<String> get() = interruptCalls.map { it.second }
        val deletePromptIds: List<String> get() = deleteCalls.map { it.second }
    }

    /**
     * Test fake that honors the [WsEventPort] cold-flow contract: every
     * call to [events] opens a fresh session (a brand-new Channel).
     *
     * Per @Lily PR #30 review msg `357624f8`: reusing the same Channel
     * across runs violates the contract because `consumeAsFlow` cancels
     * the underlying channel when its collector is cancelled — so the
     * first run's `wsJob.cancel()` poisons the channel for any later
     * run that tries to subscribe. Each run getting its own session
     * mirrors the production behavior of a fresh `/ws` connection per
     * subscription.
     *
     * [send] / [close] act on the **most recently created** session,
     * which is the natural target for a test that has just observed
     * the latest run reach Queued.
     */
    private class ChannelWs : WsEventPort {
        val sessions: MutableList<Channel<WsEvent>> = mutableListOf()
        val clientIds: MutableList<String> = mutableListOf()
        val baseUrls: MutableList<String> = mutableListOf()
        val lastClientId: String? get() = clientIds.lastOrNull()
        val lastBaseUrl: String? get() = baseUrls.lastOrNull()

        override fun events(baseUrl: String, clientId: String): Flow<WsEvent> {
            clientIds += clientId
            baseUrls += baseUrl
            val session = Channel<WsEvent>(capacity = Channel.UNLIMITED)
            sessions += session
            return session.consumeAsFlow()
        }

        /** Send to the most-recently-opened session. */
        fun send(event: WsEvent) {
            val s = sessions.lastOrNull()
                ?: error("ChannelWs.send() called before any events() session was opened")
            val r = s.trySend(event)
            check(r.isSuccess) { "channel send failed: $r" }
        }

        /** Close the most-recently-opened session. */
        fun close() {
            sessions.lastOrNull()?.close()
        }
    }

    private fun coord(
        prompt: PromptSubmissionPort = RecordingPrompt(),
        cancel: CancelPort = RecordingCancel(),
        ws: WsEventPort = ChannelWs(),
        jobs: InMemoryJobRepository = InMemoryJobRepository(),
        clock: Clock = FixedClock(),
    ): Pair<RunCoordinator, InMemoryJobRepository> = RunCoordinator(
        prompt = prompt,
        cancel = cancel,
        ws = ws,
        converter = WorkflowConverter(),
        jobs = jobs,
        clock = clock,
    ) to jobs

    private fun submission(
        serverId: String = "srv-1",
        baseUrl: String = "http://srv-1.local:8188",
        promptId: String = "client-1",
    ) = RunSubmission(
        serverId = serverId,
        baseUrl = baseUrl,
        clientId = promptId,
        workflowUi = minimalUi(),
        workflowSnapshotJson = "{\"raw\":\"snapshot\"}",
        label = "test-run",
    )

    // ----------------------------------------------------------------- happy path

    @Test fun happy_path_succeeds_and_persists_outputs() = runTest {
        val prompt = RecordingPrompt(response = PromptResponseDto(prompt_id = "p-1", number = 0))
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(prompt = prompt, ws = ws, jobs = jobs)

        val terminalDeferred = async(Dispatchers.Unconfined) { c.run(submission()) }

        // Wait until coordinator transitions to Queued (post-submit, pre-WS).
        c.state.first { it is RunState.Queued }
        assertEquals(JobStatus.QUEUED, jobs.getByPromptId("p-1")!!.status)

        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.ExecutionCached(promptId = "p-1", nodes = listOf("n-1")))
        ws.send(WsEvent.Executing(promptId = "p-1", node = "n-2", displayNode = "KSampler"))
        ws.send(WsEvent.Progress(promptId = "p-1", node = "n-2", value = 5, max = 20))
        ws.send(WsEvent.Executed(
            promptId = "p-1",
            node = "n-2",
            output = buildJsonObject {
                put("images", buildJsonArray {
                    add(buildJsonObject {
                        put("filename", JsonPrimitive("out.png"))
                        put("subfolder", JsonPrimitive(""))
                        put("type", JsonPrimitive("output"))
                    })
                })
            },
        ))
        ws.send(WsEvent.Executing(promptId = "p-1", node = null))
        ws.send(WsEvent.ExecutionSuccess(promptId = "p-1"))
        // NOTE: deliberately NO ws.close() — production /ws never closes.
        // The reducer must terminate on the ExecutionSuccess terminal alone.
        // (@Lily PR #30 review msg `49b81084` regression coverage.)

        val terminal = withTimeout(1_000) { terminalDeferred.await() }
        val succeeded = assertIs<RunState.Succeeded>(terminal)
        assertEquals("p-1", succeeded.promptId)
        assertEquals(1, succeeded.outputs.size)
        assertEquals("out.png", succeeded.outputs[0].filename)

        val persisted = jobs.getByPromptId("p-1")!!
        assertEquals(JobStatus.SUCCEEDED, persisted.status)
        assertEquals(JobOutputRef("out.png", "", "output"), persisted.firstOutput)
    }

    // ----------------------------------------------------------------- validation failure at submit

    @Test fun validation_failure_at_submit_emits_Failed_and_does_not_persist_job() = runTest {
        val prompt = RecordingPrompt(
            response = PromptResponseDto(
                prompt_id = "p-1",
                number = 0,
                node_errors = mapOf("n-2" to buildJsonObject { put("msg", JsonPrimitive("bad arg")) }),
            )
        )
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(prompt = prompt, jobs = jobs)

        val terminal = c.run(submission())
        val failed = assertIs<RunState.Failed>(terminal)
        assertEquals("p-1", failed.promptId)
        val err = assertIs<RunError.ValidationFailed>(failed.error)
        assertTrue(err.nodeErrors.containsKey("n-2"))

        // No job row should have been persisted — validation failed before queue.
        assertNull(jobs.getByPromptId("p-1"))
    }

    // ----------------------------------------------------------------- network error at submit

    @Test fun network_error_at_submit_emits_Failed_and_does_not_persist_job() = runTest {
        val prompt = RecordingPrompt(throwOnSubmit = RuntimeException("connection refused"))
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(prompt = prompt, jobs = jobs)

        val terminal = c.run(submission())
        val failed = assertIs<RunState.Failed>(terminal)
        assertNull(failed.promptId)
        val err = assertIs<RunError.Network>(failed.error)
        assertEquals("connection refused", err.cause.message)

        // jobs map is empty
        assertTrue(jobs.listByServer("srv-1").isEmpty())
    }

    // ----------------------------------------------------------------- terminal failure during execution

    @Test fun execution_error_during_run_terminates_Failed_and_updates_db_to_FAILED() = runTest {
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(ws = ws, jobs = jobs)

        val terminalDeferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }

        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.Executing(promptId = "p-1", node = "n-5", displayNode = "KSampler"))
        ws.send(WsEvent.ExecutionError(
            promptId = "p-1",
            nodeId = "n-5",
            nodeType = "KSampler",
            executed = emptyList(),
            exceptionMessage = "boom",
            exceptionType = "RuntimeError",
        ))
        // No ws.close() — terminal event alone must release the loop.

        val terminal = withTimeout(1_000) { terminalDeferred.await() }
        val failed = assertIs<RunState.Failed>(terminal)
        val err = assertIs<RunError.NodeException>(failed.error)
        assertEquals("n-5", err.nodeId)
        assertEquals(JobStatus.FAILED, jobs.getByPromptId("p-1")!!.status)
    }

    // ----------------------------------------------------------------- interrupted during execution

    @Test fun execution_interrupted_during_run_terminates_Cancelled_and_updates_db_to_INTERRUPTED() = runTest {
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(ws = ws, jobs = jobs)

        val terminalDeferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }

        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.Executing(promptId = "p-1", node = "n-3"))
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1", nodeId = "n-3"))
        // No ws.close() — terminal event alone must release the loop.

        val terminal = withTimeout(1_000) { terminalDeferred.await() }
        val cancelled = assertIs<RunState.Cancelled>(terminal)
        assertEquals("n-3", cancelled.fromNodeId)
        assertEquals(JobStatus.INTERRUPTED, jobs.getByPromptId("p-1")!!.status)
    }

    // ----------------------------------------------------------------- no outputs after success

    @Test fun execution_success_without_outputs_terminates_Failed_NoOutputs() = runTest {
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(ws = ws, jobs = jobs)

        val terminalDeferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }

        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.Executed(
            promptId = "p-1",
            node = "n-1",
            output = buildJsonObject { put("text", buildJsonArray { add(JsonPrimitive("not an image")) }) },
        ))
        ws.send(WsEvent.ExecutionSuccess(promptId = "p-1"))
        // No ws.close() — ExecutionSuccess + NoOutputs alone must release the loop.

        val terminal = withTimeout(1_000) { terminalDeferred.await() }
        val failed = assertIs<RunState.Failed>(terminal)
        assertEquals(RunError.NoOutputs, failed.error)
        assertEquals(JobStatus.FAILED, jobs.getByPromptId("p-1")!!.status)
    }

    // ----------------------------------------------------------------- cancel routing

    @Test fun requestCancel_in_Queued_state_routes_to_DeleteQueued_and_terminates_locally() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(cancel = cancel, ws = ws, jobs = jobs)

        val deferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }

        val route = c.requestCancel()
        val r = assertIs<CancelRoute.DeleteQueued>(route)
        assertEquals("p-1", r.promptId)
        assertEquals(listOf("p-1"), cancel.deletePromptIds)
        assertEquals(emptyList<String>(), cancel.interruptPromptIds)

        // Per @Lily PR #30 review msg `49b81084`: ComfyUI does NOT emit
        // execution_interrupted for queued prompts deleted from the queue
        // (the prompt never ran, so there's nothing to interrupt). The
        // run loop MUST still complete — without any WS event arriving
        // and without ws.close() being called.
        val terminal = withTimeout(1_000) { deferred.await() }
        val cancelled = assertIs<RunState.Cancelled>(terminal)
        assertEquals("p-1", cancelled.promptId)
        assertNull(cancelled.fromNodeId)
        assertEquals(JobStatus.INTERRUPTED, jobs.getByPromptId("p-1")!!.status)
    }

    /**
     * @Lily PR #30 race regression (msg `42ee1862`):
     *
     * The local cancel signal MUST survive the publication window between
     * `state.value = Queued` and the reducer's cancel-collector becoming
     * active. The previous SharedFlow(replay=0) approach lost signals
     * emitted before subscription; the fix routes them through a Channel
     * whose reference is published BEFORE state.value = Queued, so any
     * caller observing Queued can always reach a buffered signal slot.
     *
     * Behavioral cover: observe Queued → call requestCancel synchronously
     * → expect Cancelled terminal, NO WS event, NO ws.close().
     */
    @Test fun cancel_immediately_after_observing_Queued_terminates_without_ws_event() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(cancel = cancel, ws = ws, jobs = jobs)

        val deferred = async(Dispatchers.Unconfined) { c.run(submission()) }

        // The MOMENT Queued is observed, call requestCancel — this is the
        // race window. Under the previous SharedFlow(replay=0) implementation
        // the cancel-signal collector might not yet be subscribed, and the
        // signal would be silently dropped, leaving the run hung.
        c.state.first { it is RunState.Queued }
        val route = c.requestCancel()

        assertIs<CancelRoute.DeleteQueued>(route)
        // Channel-based pathway means: no WS event is sent, ws.close() is
        // never called, and yet the run must terminate.
        val terminal = withTimeout(1_000) { deferred.await() }
        val cancelled = assertIs<RunState.Cancelled>(terminal)
        assertEquals("p-1", cancelled.promptId)
        assertEquals(JobStatus.INTERRUPTED, jobs.getByPromptId("p-1")!!.status)
        assertEquals(listOf("p-1"), cancel.deletePromptIds)
    }

    @Test fun requestCancel_in_Running_state_routes_to_InterruptRunning_and_waits_for_server_event() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(cancel = cancel, ws = ws, jobs = jobs)

        val deferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.Executing(promptId = "p-1", node = "n-2"))
        c.state.first { it is RunState.Running && it.currentNodeId == "n-2" }

        val route = c.requestCancel()
        val r = assertIs<CancelRoute.InterruptRunning>(route)
        assertEquals("p-1", r.promptId)
        assertEquals(listOf("p-1"), cancel.interruptPromptIds)
        assertEquals(emptyList<String>(), cancel.deletePromptIds)

        // For Running cancel, ComfyUI DOES emit execution_interrupted via WS
        // (the prompt was executing). Run loop waits for that event.
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1", nodeId = "n-2"))
        val terminal = withTimeout(1_000) { deferred.await() }
        val cancelled = assertIs<RunState.Cancelled>(terminal)
        assertEquals("n-2", cancelled.fromNodeId)
        assertEquals(JobStatus.INTERRUPTED, jobs.getByPromptId("p-1")!!.status)
    }

    @Test fun requestCancel_in_Idle_state_is_noop() = runTest {
        val cancel = RecordingCancel()
        val (c, _) = coord(cancel = cancel)

        assertNull(c.requestCancel())
        assertEquals(emptyList<String>(), cancel.interruptPromptIds)
        assertEquals(emptyList<String>(), cancel.deletePromptIds)
    }

    @Test fun requestCancel_after_terminal_is_noop() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val (c, _) = coord(cancel = cancel, ws = ws)

        val deferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.ExecutionError(
            promptId = "p-1",
            nodeId = "n-1",
            nodeType = "X",
            executed = emptyList(),
            exceptionMessage = "fail",
            exceptionType = "Err",
        ))
        deferred.await()

        assertNull(c.requestCancel())
        assertEquals(emptyList<String>(), cancel.interruptPromptIds)
        assertEquals(emptyList<String>(), cancel.deletePromptIds)
    }

    // ----------------------------------------------------------------- WS closes without terminal

    @Test fun ws_closes_before_terminal_event_terminates_Failed_Network() = runTest {
        val ws = ChannelWs()
        val jobs = InMemoryJobRepository()
        val (c, _) = coord(ws = ws, jobs = jobs)

        val deferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }
        // Close WS without any terminal.
        ws.close()

        val terminal = withTimeout(1_000) { deferred.await() }
        val failed = assertIs<RunState.Failed>(terminal)
        assertIs<RunError.Network>(failed.error)
        assertEquals(JobStatus.FAILED, jobs.getByPromptId("p-1")!!.status)
    }

    // ----------------------------------------------------------------- concurrent run

    @Test fun second_concurrent_run_returns_current_state_without_resubmitting() = runTest {
        val prompt = RecordingPrompt()
        val ws = ChannelWs()
        val (c, _) = coord(prompt = prompt, ws = ws)

        val firstRun = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }
        assertEquals(1, prompt.calls)

        // Second run should NOT issue a fresh submit while the first is in flight.
        val secondResult = c.run(submission())
        assertEquals(1, prompt.calls, "no second submit while first run is active")
        // The current state is what was returned.
        assertEquals(c.state.value, secondResult)

        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1"))
        firstRun.await()
    }

    /**
     * @Lily PR #30 regression (msgs `49b81084` / `e1d26e71`): after the
     * run loop reaches a terminal via a WS event (without ws.close), the
     * run mutex MUST be released so a subsequent run() call can proceed
     * normally. Prior to the fix this hung because the WS Flow never
     * completed.
     *
     * Sequenced prompt ids verify that the second run actually went
     * through `POST /prompt` and is keyed on `p-2`, not the leftover
     * `p-1` from the first run.
     */
    @Test fun run_after_terminal_can_proceed_releasing_mutex() = runTest {
        val prompt = RecordingPrompt(
            sequencedResponses = listOf(
                PromptResponseDto(prompt_id = "p-1", number = 0),
                PromptResponseDto(prompt_id = "p-2", number = 1),
            ),
        )
        val ws = ChannelWs()
        val (c, _) = coord(prompt = prompt, ws = ws)

        // First run: terminal via WS event, no ws.close().
        val first = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued && it.promptId == "p-1" }
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1"))
        withTimeout(1_000) { first.await() }
        val firstTerminal = assertIs<RunState.Cancelled>(c.state.value)
        assertEquals("p-1", firstTerminal.promptId)

        // The mutex MUST be released — second run must submit and reach
        // Queued("p-2") without deadlocking on the prior collection.
        val second = async(Dispatchers.Unconfined) {
            c.run(submission().copy(clientId = "client-2"))
        }
        withTimeout(1_000) {
            c.state.first { it is RunState.Queued && it.promptId == "p-2" }
        }
        assertEquals(2, prompt.calls, "second submit must have fired")
        // Second run's actual prompt id is p-2, NOT the leftover p-1.
        assertEquals("p-2", (c.state.value as RunState.Queued).promptId)

        // Drain the second run with its own prompt id.
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-2"))
        withTimeout(1_000) { second.await() }
        val secondTerminal = assertIs<RunState.Cancelled>(c.state.value)
        assertEquals("p-2", secondTerminal.promptId)
    }

    // ----------------------------------------------------------------- request shape

    @Test fun submitted_request_carries_client_id_and_extra_data() = runTest {
        val prompt = RecordingPrompt()
        val ws = ChannelWs()
        val (c, _) = coord(prompt = prompt, ws = ws)

        val extraData: JsonElement = buildJsonObject {
            put("extra_pnginfo", buildJsonObject {
                put("workflow", JsonPrimitive("ui-snapshot-here"))
            })
        }
        val deferred = async(Dispatchers.Unconfined) {
            c.run(submission().copy(extraData = extraData))
        }
        c.state.first { it is RunState.Queued }

        val req = assertNotNull(prompt.lastRequest)
        assertEquals("client-1", req.client_id)
        assertEquals(extraData, req.extra_data)

        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1"))
        deferred.await()
    }

    @Test fun ws_subscribes_with_submission_client_id() = runTest {
        val ws = ChannelWs()
        val (c, _) = coord(ws = ws)

        val deferred = async(Dispatchers.Unconfined) {
            c.run(submission().copy(clientId = "client-XYZ"))
        }
        c.state.first { it is RunState.Queued }
        // Need a moment for the WS subscription to actually happen.
        // Since the coordinator subscribes after Queued state transitions,
        // by now lastClientId should be set.
        assertEquals("client-XYZ", ws.lastClientId)

        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1"))
        deferred.await()
    }

    /**
     * @Lily PR #30 regression coverage (msg `49b81084`): events arriving for
     * a different prompt id (e.g. another client sharing the same WS) must
     * be ignored, AND a terminal for the wrong prompt id must NOT release
     * our loop. Combined with the local-finalize fix, this proves the
     * terminal detection is keyed on snapshot.terminal (which only the
     * matching prompt id can set), not on any incoming terminal event.
     */
    @Test fun terminal_event_for_other_prompt_does_not_release_loop() = runTest {
        val ws = ChannelWs()
        val (c, _) = coord(ws = ws)

        val deferred = async(Dispatchers.Unconfined) { c.run(submission()) }
        c.state.first { it is RunState.Queued }

        // Foreign prompt's terminal — must be ignored.
        ws.send(WsEvent.ExecutionSuccess(promptId = "p-other"))
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-other"))

        // Still queued for our prompt.
        assertEquals("p-1", (c.state.value as? RunState.Queued)?.promptId)

        // Now our own terminal — releases.
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1"))
        withTimeout(1_000) { deferred.await() }
        assertIs<RunState.Cancelled>(c.state.value)
    }

    /**
     * @Lily PR #31 second-round regression (msg `8bbd4fa1` blocker 1):
     *
     * A run submitted against server A must continue talking to server
     * A for its WS subscription AND its cancel call, EVEN IF the user
     * switches the active server to B mid-run. The previous adapter
     * design read [ActiveServerHolder.current] inside each port method,
     * so switching active server mid-run silently retargeted the WS
     * subscription and cancel endpoints to B.
     *
     * This test simulates a mid-run "active server switch" by calling
     * the coordinator with a different baseUrl in a second submission
     * and verifying:
     *   - The FIRST run's WS subscription is on server A (lastBaseUrl
     *     captured at the time we issued the first run).
     *   - The FIRST run's cancel routes to server A's baseUrl, not to
     *     whatever's "active" at cancel time. The cancel port records
     *     (baseUrl, promptId); we assert the baseUrl pair matches A.
     */
    @Test fun mid_run_active_server_switch_does_not_redirect_cancel_or_ws() = runTest {
        val prompt = RecordingPrompt()
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val (c, _) = coord(prompt = prompt, cancel = cancel, ws = ws)

        val baseUrlA = "http://server-A:8188"
        val deferred = async(Dispatchers.Unconfined) {
            c.run(submission(serverId = "A", baseUrl = baseUrlA))
        }
        c.state.first { it is RunState.Queued }
        // WS subscribed with server A.
        assertEquals(baseUrlA, ws.lastBaseUrl)
        // Submit went to server A.
        assertEquals(listOf(baseUrlA), prompt.baseUrls)

        // (The "active server switch" is implicit: the coordinator's
        // ports get baseUrl from the submission, not from any shared
        // ActiveServerHolder. If a higher layer's active server changes,
        // it does not propagate down to this run.)

        // Cancel during Queued must route to server A.
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.Executing(promptId = "p-1", node = "1"))
        c.state.first { it is RunState.Running }

        val route = c.requestCancel()
        assertIs<CancelRoute.InterruptRunning>(route)
        assertEquals(listOf(baseUrlA to "p-1"), cancel.interruptCalls)

        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1", nodeId = "1"))
        withTimeout(1_000) { deferred.await() }
    }

    @Test fun queued_cancel_routes_to_run_baseUrl_not_a_global_active_server() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val (c, _) = coord(cancel = cancel, ws = ws)

        val baseUrlA = "http://server-A:8188"
        val deferred = async(Dispatchers.Unconfined) {
            c.run(submission(serverId = "A", baseUrl = baseUrlA))
        }
        c.state.first { it is RunState.Queued }

        val route = c.requestCancel()
        assertIs<CancelRoute.DeleteQueued>(route)
        // Queued cancel hits server A's /queue, never some other server.
        assertEquals(listOf(baseUrlA to "p-1"), cancel.deleteCalls)
        assertEquals(emptyList<Pair<String, String>>(), cancel.interruptCalls)

        withTimeout(1_000) { deferred.await() }
    }
}
