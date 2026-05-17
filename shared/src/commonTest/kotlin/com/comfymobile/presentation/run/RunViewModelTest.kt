package com.comfymobile.presentation.run

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.WsEvent
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.data.network.dto.PromptResponseDto
import com.comfymobile.data.persistence.InMemoryJobRepository
import com.comfymobile.domain.run.CancelPort
import com.comfymobile.domain.run.Clock
import com.comfymobile.domain.run.PromptSubmissionPort
import com.comfymobile.domain.run.RunCoordinator
import com.comfymobile.domain.run.RunState
import com.comfymobile.domain.run.WsEventPort
import com.comfymobile.domain.server.ServerInfo
import com.comfymobile.domain.workflow.WorkflowConverter
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Behavioral tests for [RunViewModel] focusing on the four @Lily T2.3
 * second-segment gates (msg `3e9e7269`):
 *
 *   Gate 1 — canSubmit requires prepared workflow + active server +
 *            non-running coordinator state.
 *   Gate 2 — banner from ConnectionState does not suppress run terminal
 *            (covered exhaustively by [RunUiStateMapperTest]; here
 *            we verify the wiring delivers both inputs).
 *   Gate 3 — projection is unidirectional (VM never writes RunState).
 *   Gate 4 — cancel goes through coordinator.requestCancel(); the VM
 *            never touches network endpoints directly.
 */
class RunViewModelTest {

    private fun envelope(label: String = "test workflow"): WorkflowEnvelope =
        WorkflowEnvelope(
            original = buildJsonObject {
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(1))
                        put("type", JsonPrimitive("CheckpointLoaderSimple"))
                        put("title", JsonPrimitive("Model"))
                        put("widgets_values", buildJsonArray { add(JsonPrimitive("model.safetensors")) })
                    })
                })
                put("links", buildJsonArray {})
            },
            format = WorkflowFormat.UI,
            metadata = WorkflowMetadata(
                label = label,
                createdAtEpochMs = 1_700_000_000_000L,
                lastEditedAtEpochMs = 1_700_000_000_000L,
            ),
        )

    private fun activeServerHolder(server: ServerInfo? = null): ActiveServerHolder {
        val holder = ActiveServerHolder()
        if (server != null) {
            holder.setActive(server)
        }
        return holder
    }

    private val testServer = ServerInfo(
        serverId = "127.0.0.1:8188",
                baseUrl = "http://127.0.0.1:8188",
        host = "127.0.0.1",
        port = 8188,
        label = "Local",
        lastConnectedAtEpochMs = 1_700_000_000_000L,
    )

    private class RecordingPrompt(
        private val response: PromptResponseDto = PromptResponseDto(prompt_id = "p-1", number = 0),
    ) : PromptSubmissionPort {
        var calls = 0
        val baseUrls = mutableListOf<String>()
        override suspend fun submit(baseUrl: String, request: PromptRequestDto): PromptResponseDto {
            calls += 1
            baseUrls += baseUrl
            return response
        }
    }

    private class RecordingCancel : CancelPort {
        val interruptCalls = mutableListOf<Pair<String, String>>()
        val deleteCalls = mutableListOf<Pair<String, String>>()
        override suspend fun interruptRunning(baseUrl: String, promptId: String) {
            interruptCalls += baseUrl to promptId
        }
        override suspend fun deleteQueued(baseUrl: String, promptId: String) {
            deleteCalls += baseUrl to promptId
        }
        val interruptPromptIds: List<String> get() = interruptCalls.map { it.second }
        val deletePromptIds: List<String> get() = deleteCalls.map { it.second }
    }

    private class ChannelWs : WsEventPort {
        val sessions = mutableListOf<Channel<WsEvent>>()
        override fun events(baseUrl: String, clientId: String): Flow<WsEvent> {
            val c = Channel<WsEvent>(capacity = Channel.UNLIMITED)
            sessions += c
            return c.consumeAsFlow()
        }
        fun send(event: WsEvent) {
            sessions.last().trySend(event).getOrThrow()
        }
    }

    private fun vm(
        prompt: PromptSubmissionPort = RecordingPrompt(),
        cancel: CancelPort = RecordingCancel(),
        ws: WsEventPort = ChannelWs(),
        jobs: InMemoryJobRepository = InMemoryJobRepository(),
        connection: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected),
        activeServer: ActiveServerHolder = activeServerHolder(testServer),
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<RunViewModel, RunCoordinator> {
        val coordinator = RunCoordinator(
            prompt = prompt,
            cancel = cancel,
            ws = ws,
            converter = WorkflowConverter(),
            jobs = jobs,
            clock = Clock { 1_700_000_000_000L },
        )
        return RunViewModel(
            coordinator = coordinator,
            activeServer = activeServer,
            connectionState = connection,
            clientId = "test-client",
            scope = scope,
        ) to coordinator
    }

    // ----------------------------------------------------------------- gate 1

    @Test fun canSubmit_is_false_initially_without_prepared_workflow() = runTest {
        val (vm, _) = vm(scope = this)
        // Wait for stateIn to publish initial value.
        val state = vm.uiState.first { true }
        assertFalse(state.canSubmit)
    }

    @Test fun canSubmit_becomes_true_after_prepare_with_active_server_present() = runTest {
        val (vm, _) = vm(scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope(), label = "wf"))
        val state = vm.uiState.first { it.canSubmit }
        assertTrue(state.canSubmit)
    }

    @Test fun canSubmit_remains_false_without_active_server_even_with_prepared_workflow() = runTest {
        val (vm, _) = vm(
            activeServer = activeServerHolder(server = null),
            scope = this,
        )
        vm.prepare(PreparedWorkflow(envelope = envelope()))
        val state = vm.uiState.first { true }
        assertFalse(state.canSubmit)
    }

    // ----------------------------------------------------------------- gate 4

    @Test fun requestCancel_then_confirmCancel_routes_through_coordinator_for_Queued() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val (vm, coordinator) = vm(cancel = cancel, ws = ws, scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope()))

        val terminalDeferred = async(Dispatchers.Unconfined) { coordinator.run(
            com.comfymobile.domain.run.RunSubmission(
                serverId = "127.0.0.1:8188",
                baseUrl = "http://127.0.0.1:8188",
                clientId = "test-client",
                workflowUi = com.comfymobile.domain.workflow.WorkflowGraph.Ui(
                    (envelope().original as JsonObject),
                ),
            )
        ) }

        coordinator.state.first { it is RunState.Queued }

        // VM intents
        vm.requestCancel()
        vm.uiState.first { it.cancelConfirmOpen }
        vm.confirmCancel()

        // Terminal lands via local-finalize through coordinator.requestCancel
        val terminal = withTimeout(1_000) { terminalDeferred.await() }
        assertIs<RunState.Cancelled>(terminal)
        // Gate 4: cancel route was the queued one (deleteQueued), not interrupt.
        assertEquals(listOf("p-1"), cancel.deletePromptIds)
        assertEquals(emptyList<String>(), cancel.interruptPromptIds)
    }

    @Test fun requestCancel_then_confirmCancel_routes_through_coordinator_for_Running() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val (vm, coordinator) = vm(cancel = cancel, ws = ws, scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope()))

        val terminalDeferred = async(Dispatchers.Unconfined) { coordinator.run(
            com.comfymobile.domain.run.RunSubmission(
                serverId = "127.0.0.1:8188",
                baseUrl = "http://127.0.0.1:8188",
                clientId = "test-client",
                workflowUi = com.comfymobile.domain.workflow.WorkflowGraph.Ui(
                    (envelope().original as JsonObject),
                ),
            )
        ) }

        coordinator.state.first { it is RunState.Queued }
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.Executing(promptId = "p-1", node = "1"))
        coordinator.state.first { it is RunState.Running && it.currentNodeId == "1" }

        vm.requestCancel()
        vm.uiState.first { it.cancelConfirmOpen }
        vm.confirmCancel()

        // Running cancel waits for server event.
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1", nodeId = "1"))
        val terminal = withTimeout(1_000) { terminalDeferred.await() }
        assertIs<RunState.Cancelled>(terminal)
        // Gate 4: cancel route was interrupt, not delete.
        assertEquals(listOf("p-1"), cancel.interruptPromptIds)
        assertEquals(emptyList<String>(), cancel.deletePromptIds)
    }

    @Test fun requestCancel_in_Idle_does_not_open_confirm() = runTest {
        val (vm, _) = vm(scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope()))
        vm.uiState.first { it.canSubmit }

        vm.requestCancel()
        // Should NOT open — Idle is not cancellable.
        val state = vm.uiState.first { true }
        assertFalse(state.cancelConfirmOpen)
    }

    // ----------------------------------------------------------------- Lily blocker 1: dismissTerminal hides sheet

    @Test fun dismissTerminal_hides_the_terminal_sheet_even_though_RunState_is_terminal() = runTest {
        val ws = ChannelWs()
        val (vm, coordinator) = vm(ws = ws, scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope()))

        val terminalDeferred = async(Dispatchers.Unconfined) { coordinator.run(
            com.comfymobile.domain.run.RunSubmission(
                serverId = "127.0.0.1:8188",
                baseUrl = "http://127.0.0.1:8188",
                clientId = "test-client",
                workflowUi = com.comfymobile.domain.workflow.WorkflowGraph.Ui(envelope().original as JsonObject),
            )
        ) }
        coordinator.state.first { it is RunState.Queued }
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.ExecutionError(
            promptId = "p-1",
            nodeId = "1",
            nodeType = "X",
            executed = emptyList(),
            exceptionMessage = "boom",
            exceptionType = "RuntimeError",
        ))
        withTimeout(1_000) { terminalDeferred.await() }

        // Terminal sheet is visible until dismissed.
        assertIs<RunUiState.TerminalView.Failure>(vm.uiState.first { it.terminal != null }.terminal)

        vm.dismissTerminal()
        val afterDismiss = vm.uiState.first { it.terminal == null }
        // Sheet hidden, but the phase remains Failed.
        assertIs<RunUiState.Phase.Failed>(afterDismiss.phase)
    }

    /**
     * @Lily PR #31 second-round regression (msg `8bbd4fa1` blocker 2):
     *
     * Dismissing a Failed/Cancelled sheet then loading another workflow
     * must NOT resurrect the dismissed sheet. The previous fix used a
     * boolean reset on `prepare()` which had the wrong sign — dismissing
     * the sheet then loading workflow B would bring back A's stale
     * failure dialog. Keyed-by-promptId dismissal solves this.
     */
    @Test fun prepare_after_dismiss_does_NOT_resurrect_the_dismissed_terminal() = runTest {
        val ws = ChannelWs()
        val (vm, coordinator) = vm(ws = ws, scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope()))

        val deferred = async(Dispatchers.Unconfined) { coordinator.run(
            com.comfymobile.domain.run.RunSubmission(
                serverId = "127.0.0.1:8188",
                baseUrl = "http://127.0.0.1:8188",
                clientId = "test-client",
                workflowUi = com.comfymobile.domain.workflow.WorkflowGraph.Ui(envelope().original as JsonObject),
            )
        ) }
        coordinator.state.first { it is RunState.Queued }
        ws.send(WsEvent.ExecutionStart(promptId = "p-1"))
        ws.send(WsEvent.ExecutionError(
            promptId = "p-1", nodeId = "1", nodeType = "X", executed = emptyList(),
            exceptionMessage = "boom", exceptionType = "RuntimeError",
        ))
        withTimeout(1_000) { deferred.await() }

        // User sees the terminal sheet, dismisses it.
        vm.uiState.first { it.terminal != null }
        vm.dismissTerminal()
        val afterDismiss = vm.uiState.first { it.terminal == null }
        assertIs<RunUiState.Phase.Failed>(afterDismiss.phase)

        // User loads a different workflow. The prior dismissed terminal
        // MUST NOT resurface — the dismissal is keyed to the prior
        // run's promptId, so until a new submit creates a new run with
        // a new promptId, the sheet stays hidden.
        vm.prepare(PreparedWorkflow(envelope = envelope(label = "another")))
        val afterPrepare = vm.uiState.first { true }
        assertNull(afterPrepare.terminal)
        // Phase stays Failed (RunState.value didn't change), but the
        // modal sheet stays dismissed.
        assertIs<RunUiState.Phase.Failed>(afterPrepare.phase)
    }

    // ----------------------------------------------------------------- Lily blocker 4: API-only envelope CTA gate

    @Test fun canSubmit_is_false_when_only_API_format_envelope_is_prepared() = runTest {
        val (vm, _) = vm(scope = this)
        val apiOnly = WorkflowEnvelope(
            original = buildJsonObject {
                put("1", buildJsonObject {
                    put("class_type", JsonPrimitive("CheckpointLoaderSimple"))
                    put("inputs", buildJsonObject {
                        put("ckpt_name", JsonPrimitive("model.safetensors"))
                    })
                })
            },
            format = WorkflowFormat.API,
            metadata = WorkflowMetadata(
                label = "api-only workflow",
                createdAtEpochMs = 1_700_000_000_000L,
                lastEditedAtEpochMs = 1_700_000_000_000L,
            ),
        )
        vm.prepare(PreparedWorkflow(envelope = apiOnly))
        val state = vm.uiState.first { true }
        // CTA must NOT be enabled — onSubmit would silently no-op for
        // an API-only envelope.
        assertFalse(state.canSubmit)
    }

    @Test fun dismissCancel_closes_confirm_without_triggering_coordinator() = runTest {
        val cancel = RecordingCancel()
        val ws = ChannelWs()
        val (vm, coordinator) = vm(cancel = cancel, ws = ws, scope = this)
        vm.prepare(PreparedWorkflow(envelope = envelope()))

        val terminalDeferred = async(Dispatchers.Unconfined) { coordinator.run(
            com.comfymobile.domain.run.RunSubmission(
                serverId = "127.0.0.1:8188",
                baseUrl = "http://127.0.0.1:8188",
                clientId = "test-client",
                workflowUi = com.comfymobile.domain.workflow.WorkflowGraph.Ui(
                    (envelope().original as JsonObject),
                ),
            )
        ) }
        coordinator.state.first { it is RunState.Queued }

        vm.requestCancel()
        vm.uiState.first { it.cancelConfirmOpen }
        vm.dismissCancel()
        // Sheet closes but no cancel API was invoked.
        vm.uiState.first { !it.cancelConfirmOpen }
        assertEquals(emptyList<String>(), cancel.deletePromptIds)
        assertEquals(emptyList<String>(), cancel.interruptPromptIds)

        // Drain so the test scope completes cleanly.
        ws.send(WsEvent.ExecutionInterrupted(promptId = "p-1"))
        withTimeout(1_000) { terminalDeferred.await() }
    }
}
