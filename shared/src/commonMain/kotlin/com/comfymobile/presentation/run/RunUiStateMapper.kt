package com.comfymobile.presentation.run

import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.domain.run.RunError
import com.comfymobile.domain.run.RunState
import com.comfymobile.domain.run.toRuntimeStatusMap
import com.comfymobile.presentation.connection.ConnectionLanguage

/**
 * Pure-function projection from the run lifecycle + connection state +
 * prepared-workflow gate into [RunUiState].
 *
 * Unidirectional (gate 3): the output is data only; no side-effects,
 * no callbacks, no path back into [RunState].
 *
 * Independence (gate 2): [BranchBanner] is computed from
 * [ConnectionState] alone. A terminal [RunState] always renders its own
 * `Phase.Succeeded` / `Phase.Failed` / `Phase.Cancelled` regardless of
 * the connection state. The banner can co-exist with a terminal phase
 * (so the user sees "you were offline at the end" context) but it
 * cannot suppress or override the terminal.
 *
 * CTA gate (gate 1): [canSubmit] is true only when a workflow has been
 * prepared, an active server is set, AND [RunState] is in a state that
 * permits a fresh submit (Idle or terminal).
 */
object RunUiStateMapper {

    fun project(
        runState: RunState,
        connectionState: ConnectionState,
        hasPreparedWorkflow: Boolean,
        hasActiveServer: Boolean,
        cancelConfirmOpen: Boolean = false,
        workflowTitle: String? = null,
        nodeDisplayNameByNodeId: (String) -> String? = { null },
        language: ConnectionLanguage = ConnectionLanguage.En,
        terminalDismissed: Boolean = false,
    ): RunUiState {
        val phase = projectPhase(runState, workflowTitle, nodeDisplayNameByNodeId)
        return RunUiState(
            phase = phase,
            branchBanner = projectBanner(connectionState),
            canSubmit = hasPreparedWorkflow && hasActiveServer && canSubmitFromState(runState),
            canCancel = canCancelFromState(runState),
            cancelConfirmOpen = cancelConfirmOpen,
            runtimeStatusByNode = runState.toRuntimeStatusMap(),
            lastOutputThumbnail = (runState as? RunState.Running)?.firstOutput
                ?: (runState as? RunState.Succeeded)?.outputs?.firstOrNull(),
            // Suppress the terminal sheet when the user has dismissed
            // it (@Lily PR #31 blocker 1). The underlying RunState is
            // still terminal — only the modal sheet hides.
            terminal = if (terminalDismissed) null
                else projectTerminalView(runState, nodeDisplayNameByNodeId, language),
            language = language,
        )
    }

    // ----------------------------------------------------------------- phase

    private fun projectPhase(
        runState: RunState,
        workflowTitle: String?,
        nodeDisplayNameByNodeId: (String) -> String?,
    ): RunUiState.Phase = when (runState) {
        is RunState.Idle -> RunUiState.Phase.Idle(workflowTitle)
        is RunState.Submitting -> RunUiState.Phase.Submitting
        is RunState.Queued -> RunUiState.Phase.Queued(
            promptId = runState.promptId,
            queuePosition = runState.queuePosition,
        )
        is RunState.Running -> RunUiState.Phase.Running(
            promptId = runState.promptId,
            currentNodeId = runState.currentNodeId,
            currentNodeDisplayName = runState.currentNodeDisplayName
                ?: runState.currentNodeId?.let(nodeDisplayNameByNodeId),
            progress = runState.nodeProgress?.let {
                RunUiState.NodeProgressView(
                    nodeId = it.nodeId,
                    value = it.value,
                    max = it.max,
                )
            },
        )
        is RunState.Succeeded -> RunUiState.Phase.Succeeded(
            promptId = runState.promptId,
            outputs = runState.outputs,
        )
        is RunState.Failed -> RunUiState.Phase.Failed(promptId = runState.promptId)
        is RunState.Cancelled -> RunUiState.Phase.Cancelled(promptId = runState.promptId)
    }

    // ----------------------------------------------------------------- banner

    /**
     * Project [ConnectionState] into a layered banner.
     *
     * Banner kinds (per T0.4 §3.3 / §3.4 / §3.5):
     *  - [Connected]                  → no banner
     *  - [Reconnecting(LAN_FLAKE)]    → silent reconnecting
     *  - [Reconnecting(BACKGROUND…)]  → explicit "checking…" banner
     *  - [Lost]                       → terminal offline banner
     */
    private fun projectBanner(connectionState: ConnectionState): RunUiState.BranchBanner =
        when (connectionState) {
            is ConnectionState.Connected -> RunUiState.BranchBanner.None
            is ConnectionState.Reconnecting -> when (connectionState.reason) {
                ReconnectReason.LAN_FLAKE -> RunUiState.BranchBanner.Reconnecting
                ReconnectReason.BACKGROUND_RESUMED -> RunUiState.BranchBanner.BackgroundResuming
            }
            is ConnectionState.Lost -> RunUiState.BranchBanner.Offline(connectionState)
        }

    // ----------------------------------------------------------------- CTAs

    /**
     * A fresh submit is allowed in Idle or any terminal state (Succeeded
     * / Failed / Cancelled). The run mutex inside RunCoordinator also
     * enforces this — but evaluating it here lets the CTA disable
     * proactively without round-tripping through coordinator.
     */
    private fun canSubmitFromState(runState: RunState): Boolean = when (runState) {
        is RunState.Idle,
        is RunState.Succeeded,
        is RunState.Failed,
        is RunState.Cancelled -> true
        is RunState.Submitting,
        is RunState.Queued,
        is RunState.Running -> false
    }

    /** Cancel is meaningful only while a run is in flight on the server side. */
    private fun canCancelFromState(runState: RunState): Boolean =
        runState is RunState.Queued || runState is RunState.Running

    // ----------------------------------------------------------------- terminal sheet

    private fun projectTerminalView(
        runState: RunState,
        nodeDisplayNameByNodeId: (String) -> String?,
        language: ConnectionLanguage,
    ): RunUiState.TerminalView? = when (runState) {
        is RunState.Failed -> {
            val err = runState.error
            val failingNode = (err as? RunError.NodeException)?.let {
                nodeDisplayNameByNodeId(it.nodeId) ?: it.nodeType
            }
            val traceback = (err as? RunError.NodeException)?.traceback
            RunUiState.TerminalView.Failure(
                promptId = runState.promptId,
                title = errorTitle(err, language),
                message = errorMessage(err, language),
                failingNodeDisplayName = failingNode,
                tracebackJson = traceback,
            )
        }
        is RunState.Cancelled -> RunUiState.TerminalView.Cancelled(promptId = runState.promptId)
        else -> null
    }

    /**
     * Resolve the failure-sheet title for an error kind. Strings live
     * in [RunCopy] so a copy update lands in one place; the mapper just
     * picks the right [com.comfymobile.presentation.connection.LocalizedText].
     */
    private fun errorTitle(err: RunError, language: ConnectionLanguage): String = when (err) {
        is RunError.ValidationFailed -> RunCopy.errorTitleValidation.resolve(language)
        is RunError.NodeException -> RunCopy.errorTitleNodeException.resolve(language)
        is RunError.Network -> RunCopy.errorTitleNetwork.resolve(language)
        is RunError.NoOutputs -> RunCopy.errorTitleNoOutputs.resolve(language)
    }

    private fun errorMessage(err: RunError, language: ConnectionLanguage): String = when (err) {
        is RunError.ValidationFailed -> RunCopy.errorMessageValidation(
            failingNodeIds = err.nodeErrors.keys.joinToString(", "),
            language = language,
        )
        is RunError.NodeException -> err.exceptionMessage.ifBlank { err.exceptionType }
        is RunError.Network -> err.cause.message
            ?: err.cause::class.simpleName
            ?: RunCopy.errorMessageNetworkFallback(language)
        is RunError.NoOutputs -> RunCopy.errorMessageNoOutputs(language)
    }
}
