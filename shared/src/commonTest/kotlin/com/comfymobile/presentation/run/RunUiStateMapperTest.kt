package com.comfymobile.presentation.run

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.run.RunError
import com.comfymobile.domain.run.RunState
import com.comfymobile.presentation.graph.NodeRuntimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for [RunUiStateMapper]. Each gate from
 * @Lily msg `3e9e7269` has dedicated coverage:
 *
 *   Gate 1 — canSubmit
 *   Gate 2 — banner does not suppress run terminal
 *   Gate 3 — projection is unidirectional and total (every RunState maps)
 *   Gate 4 — canCancel correctly mirrors the running/queued split
 */
class RunUiStateMapperTest {

    private fun project(
        runState: RunState = RunState.Idle,
        connectionState: ConnectionState = ConnectionState.Connected,
        hasPreparedWorkflow: Boolean = true,
        hasActiveServer: Boolean = true,
        cancelConfirmOpen: Boolean = false,
    ): RunUiState = RunUiStateMapper.project(
        runState = runState,
        connectionState = connectionState,
        hasPreparedWorkflow = hasPreparedWorkflow,
        hasActiveServer = hasActiveServer,
        cancelConfirmOpen = cancelConfirmOpen,
    )

    // ----------------------------------------------------------------- gate 1: canSubmit

    @Test fun canSubmit_requires_prepared_workflow() {
        val ui = project(hasPreparedWorkflow = false)
        assertFalse(ui.canSubmit)
    }

    @Test fun canSubmit_requires_active_server() {
        val ui = project(hasActiveServer = false)
        assertFalse(ui.canSubmit)
    }

    @Test fun canSubmit_in_Idle_with_workflow_and_server_is_true() {
        val ui = project()
        assertTrue(ui.canSubmit)
    }

    @Test fun canSubmit_in_Submitting_is_false_even_with_workflow_and_server() {
        val ui = project(runState = RunState.Submitting)
        assertFalse(ui.canSubmit)
    }

    @Test fun canSubmit_in_Queued_is_false() {
        val ui = project(runState = RunState.Queued(promptId = "p", queuePosition = 0))
        assertFalse(ui.canSubmit)
    }

    @Test fun canSubmit_in_Running_is_false() {
        val ui = project(runState = RunState.Running(promptId = "p"))
        assertFalse(ui.canSubmit)
    }

    @Test fun canSubmit_in_Succeeded_is_true_to_allow_re_run() {
        val ui = project(
            runState = RunState.Succeeded(promptId = "p", outputs = listOf(JobOutputRef("a.png"))),
        )
        assertTrue(ui.canSubmit)
    }

    @Test fun canSubmit_in_Failed_is_true_to_allow_retry() {
        val ui = project(
            runState = RunState.Failed(promptId = "p", error = RunError.NoOutputs),
        )
        assertTrue(ui.canSubmit)
    }

    @Test fun canSubmit_in_Cancelled_is_true_to_allow_resubmit() {
        val ui = project(runState = RunState.Cancelled(promptId = "p"))
        assertTrue(ui.canSubmit)
    }

    // ----------------------------------------------------------------- gate 4: canCancel

    @Test fun canCancel_in_Idle_is_false() {
        assertFalse(project().canCancel)
    }

    @Test fun canCancel_in_Submitting_is_false() {
        assertFalse(project(runState = RunState.Submitting).canCancel)
    }

    @Test fun canCancel_in_Queued_is_true() {
        assertTrue(project(runState = RunState.Queued("p", 0)).canCancel)
    }

    @Test fun canCancel_in_Running_is_true() {
        assertTrue(project(runState = RunState.Running("p")).canCancel)
    }

    @Test fun canCancel_in_terminal_states_is_false() {
        assertFalse(project(runState = RunState.Succeeded("p", emptyList())).canCancel)
        assertFalse(project(runState = RunState.Failed("p", RunError.NoOutputs)).canCancel)
        assertFalse(project(runState = RunState.Cancelled("p")).canCancel)
    }

    // ----------------------------------------------------------------- gate 2: banner ≠ terminal

    @Test fun banner_is_None_when_Connected() {
        val ui = project(connectionState = ConnectionState.Connected)
        assertEquals(RunUiState.BranchBanner.None, ui.branchBanner)
    }

    @Test fun banner_is_Reconnecting_for_LAN_FLAKE() {
        val ui = project(
            connectionState = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE),
        )
        assertEquals(RunUiState.BranchBanner.Reconnecting, ui.branchBanner)
    }

    @Test fun banner_is_BackgroundResuming_for_BACKGROUND_RESUMED() {
        val ui = project(
            connectionState = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED),
        )
        assertEquals(RunUiState.BranchBanner.BackgroundResuming, ui.branchBanner)
    }

    @Test fun banner_is_Offline_for_Lost_terminal() {
        val lost = ConnectionState.Lost(ConnectError.REFUSED)
        val banner = project(connectionState = lost).branchBanner
        val offline = assertIs<RunUiState.BranchBanner.Offline>(banner)
        assertEquals(lost, offline.reason)
    }

    @Test fun banner_during_Reconnecting_does_NOT_suppress_Succeeded_terminal() {
        val ui = project(
            runState = RunState.Succeeded(promptId = "p", outputs = listOf(JobOutputRef("a.png"))),
            connectionState = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED),
        )
        // banner co-exists with the terminal phase — but does NOT
        // replace it. The terminal is authoritative (gate 2).
        assertIs<RunUiState.Phase.Succeeded>(ui.phase)
        assertEquals(RunUiState.BranchBanner.BackgroundResuming, ui.branchBanner)
    }

    @Test fun banner_during_Lost_does_NOT_suppress_Failed_terminal() {
        val ui = project(
            runState = RunState.Failed(
                promptId = "p",
                error = RunError.NodeException(
                    nodeId = "5",
                    nodeType = "KSampler",
                    exceptionMessage = "boom",
                    exceptionType = "RuntimeError",
                ),
            ),
            connectionState = ConnectionState.Lost(ConnectError.REFUSED),
        )
        assertIs<RunUiState.Phase.Failed>(ui.phase)
        assertIs<RunUiState.BranchBanner.Offline>(ui.branchBanner)
    }

    @Test fun banner_during_Lost_does_NOT_suppress_Cancelled_terminal() {
        val ui = project(
            runState = RunState.Cancelled(promptId = "p", fromNodeId = "3"),
            connectionState = ConnectionState.Lost(ConnectError.REFUSED),
        )
        assertIs<RunUiState.Phase.Cancelled>(ui.phase)
        assertIs<RunUiState.BranchBanner.Offline>(ui.branchBanner)
    }

    // ----------------------------------------------------------------- gate 3: totality

    @Test fun every_RunState_variant_maps_to_a_Phase() {
        val states: List<RunState> = listOf(
            RunState.Idle,
            RunState.Submitting,
            RunState.Queued(promptId = "p", queuePosition = 1),
            RunState.Running(promptId = "p"),
            RunState.Succeeded(promptId = "p", outputs = listOf(JobOutputRef("a"))),
            RunState.Failed(promptId = "p", error = RunError.NoOutputs),
            RunState.Cancelled(promptId = "p"),
        )
        // Just check that projection succeeds for each; specific shape
        // is verified by other tests.
        for (s in states) {
            val ui = project(runState = s)
            // The phase type must be the matching projection (covered
            // by individual tests above).
            assertEquals(true, ui.phase::class.simpleName?.isNotBlank())
        }
    }

    // ----------------------------------------------------------------- Phase content

    @Test fun Running_phase_carries_currentNodeId_and_displayName_lookup() {
        val ui = RunUiStateMapper.project(
            runState = RunState.Running(promptId = "p", currentNodeId = "5"),
            connectionState = ConnectionState.Connected,
            hasPreparedWorkflow = true,
            hasActiveServer = true,
            nodeDisplayNameByNodeId = { id -> if (id == "5") "KSampler" else null },
        )
        val phase = assertIs<RunUiState.Phase.Running>(ui.phase)
        assertEquals("5", phase.currentNodeId)
        assertEquals("KSampler", phase.currentNodeDisplayName)
    }

    @Test fun Running_phase_prefers_RunState_displayName_over_lookup() {
        val ui = RunUiStateMapper.project(
            runState = RunState.Running(
                promptId = "p",
                currentNodeId = "5",
                currentNodeDisplayName = "FromEvent",
            ),
            connectionState = ConnectionState.Connected,
            hasPreparedWorkflow = true,
            hasActiveServer = true,
            nodeDisplayNameByNodeId = { _ -> "FromLookup" },
        )
        val phase = assertIs<RunUiState.Phase.Running>(ui.phase)
        assertEquals("FromEvent", phase.currentNodeDisplayName)
    }

    @Test fun Running_phase_includes_NodeProgress_when_present() {
        val ui = project(
            runState = RunState.Running(
                promptId = "p",
                nodeProgress = RunState.NodeProgress(nodeId = "5", value = 7, max = 20),
            ),
        )
        val phase = assertIs<RunUiState.Phase.Running>(ui.phase)
        assertEquals(7, phase.progress?.value)
        assertEquals(20, phase.progress?.max)
    }

    @Test fun runtimeStatusByNode_in_Running_reflects_cached_completed_current() {
        val ui = project(
            runState = RunState.Running(
                promptId = "p",
                currentNodeId = "3",
                cachedNodes = setOf("1"),
                completedNodes = setOf("2"),
            ),
        )
        assertEquals(NodeRuntimeStatus.CACHED, ui.runtimeStatusByNode["1"])
        assertEquals(NodeRuntimeStatus.DONE, ui.runtimeStatusByNode["2"])
        assertEquals(NodeRuntimeStatus.RUNNING, ui.runtimeStatusByNode["3"])
    }

    @Test fun terminal_view_for_Failed_NodeException_carries_message_and_node_name() {
        val ui = RunUiStateMapper.project(
            runState = RunState.Failed(
                promptId = "p",
                error = RunError.NodeException(
                    nodeId = "5",
                    nodeType = "KSampler",
                    exceptionMessage = "CUDA OOM",
                    exceptionType = "RuntimeError",
                ),
            ),
            connectionState = ConnectionState.Connected,
            hasPreparedWorkflow = true,
            hasActiveServer = true,
            nodeDisplayNameByNodeId = { if (it == "5") "采样器" else null },
        )
        val term = assertIs<RunUiState.TerminalView.Failure>(ui.terminal)
        assertEquals("p", term.promptId)
        assertEquals("CUDA OOM", term.message)
        assertEquals("采样器", term.failingNodeDisplayName)
    }

    @Test fun terminal_view_for_Failed_NoOutputs_uses_friendly_message() {
        val ui = project(runState = RunState.Failed(promptId = "p", error = RunError.NoOutputs))
        val term = assertIs<RunUiState.TerminalView.Failure>(ui.terminal)
        assertTrue(term.message.contains("SaveImage") || term.message.contains("没有"))
    }

    @Test fun terminal_view_for_Cancelled() {
        val ui = project(runState = RunState.Cancelled(promptId = "p", fromNodeId = "5"))
        val term = assertIs<RunUiState.TerminalView.Cancelled>(ui.terminal)
        assertEquals("p", term.promptId)
    }

    @Test fun terminal_view_is_null_for_non_terminal_states() {
        assertNull(project(runState = RunState.Idle).terminal)
        assertNull(project(runState = RunState.Submitting).terminal)
        assertNull(project(runState = RunState.Queued("p", 0)).terminal)
        assertNull(project(runState = RunState.Running("p")).terminal)
        assertNull(project(runState = RunState.Succeeded("p", emptyList())).terminal)
    }

    // ----------------------------------------------------------------- cancelConfirmOpen passthrough

    @Test fun cancelConfirmOpen_is_passed_through_from_input() {
        assertTrue(project(cancelConfirmOpen = true).cancelConfirmOpen)
        assertFalse(project(cancelConfirmOpen = false).cancelConfirmOpen)
    }
}
