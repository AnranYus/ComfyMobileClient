package com.comfymobile

import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.workflow.WorkflowEnvelope

/**
 * The MVP loop's three exclusive overlay states layered on top of the
 * always-on connect + workflow-import surface. Per @Lily T2.3 follow-up
 * gate 1 (msg `39168de4`): navigation is the host's concern; it never
 * reaches into [com.comfymobile.domain.run.RunCoordinator] directly. The
 * host only listens for `RunState.Succeeded` via [RunRoute.onSuccess].
 *
 * MVP loop transitions:
 *   Idle ──(user taps Run on a loaded workflow)──→ Running(envelope)
 *   Running ──(RunCoordinator.state.value = Succeeded)──→ Gallery(outputs)
 *   Running ──(user dismisses terminal sheet)──→ Idle
 *   Gallery ──(user taps back)──→ Idle
 *
 * Failed / Cancelled terminals stay on [Running] until the user
 * dismisses the sheet — [RunRoute] then fires `onClose` to land back
 * on [Idle]. The terminal is authoritative; the host does not navigate
 * automatically on a failure (the user might want to inspect the
 * traceback first).
 */
internal sealed interface AppScreen {

    /** Default: only connect surface + workflow import FAB visible. */
    data object Idle : AppScreen

    /**
     * Run flow active for [envelope]. RunRoute hosts the lifecycle;
     * coordinator owns the IO.
     */
    data class Running(val envelope: WorkflowEnvelope) : AppScreen

    /**
     * Output gallery showing the outputs of a just-completed run.
     * [promptId] is preserved so the gallery can request favorite /
     * share semantics via JobRepository (Andy T2.4 second segment).
     */
    data class Gallery(
        val promptId: String?,
        val outputs: List<JobOutputRef>,
    ) : AppScreen
}

internal fun canShowRunShortcut(
    selectedWorkflowId: String?,
    hasActiveServer: Boolean,
    screen: AppScreen,
): Boolean = selectedWorkflowId != null &&
    hasActiveServer &&
    screen is AppScreen.Idle

internal fun shouldClearSelectedWorkflowAfterDelete(
    selectedWorkflowId: String?,
    deletedWorkflowId: String,
): Boolean = selectedWorkflowId == deletedWorkflowId
