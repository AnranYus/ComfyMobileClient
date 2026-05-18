package com.comfymobile

import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.workflow.WorkflowEnvelope

/**
 * The MVP loop's overlay states layered on top of the always-on
 * connect + workflow-library surface. Per @Lily T2.3 follow-up gate
 * 1 (msg `39168de4`): navigation is the host's concern; it never
 * reaches into [com.comfymobile.domain.run.RunCoordinator] directly.
 * The host only listens for `RunState.Succeeded` via [RunRoute.onSuccess].
 *
 * Phase 2 close-out nav flow (per @Ores T2.7 §1.10, locked by
 * @Priestess msg `e79c6991`):
 *
 *   Connect ──(active server)──→ LibraryRoute (always-on background)
 *   Library ──(tap row)────────→ Graph(workflowId)
 *   Graph   ──(Run FAB)────────→ Running(envelope)
 *   Graph   ──(back arrow)─────→ Idle (Library back-of-stack)
 *   Running ──(RunState.Succeeded)──→ Gallery(promptId, outputs)
 *   Running ──(user dismisses terminal)──→ Idle
 *   Gallery ──(back)───────────→ Idle
 *
 * Failed / Cancelled terminals stay on [Running] until the user
 * dismisses the sheet — [RunRoute] then fires `onClose` to land back
 * on [Idle]. The terminal is authoritative; the host does not navigate
 * automatically on a failure.
 *
 * Per @Ores §1.10 acceptance contract: the Run FAB is the **only**
 * Run entry point post-T2.6 and it lives inside [Graph]. The previous
 * App.kt bottom-start Run FAB was removed in this PR.
 */
internal sealed interface AppScreen {

    /** Default: connect surface (when no active server) or library. */
    data object Idle : AppScreen

    /**
     * Workflow graph view per @Ores T2.7 §1.10. Hosts
     * `InteractiveGraphCanvas` plus the page-scoped `ParamEditorOverlay`.
     * Entry from LibraryRoute tap-row.
     *
     * @property workflowId The persisted workflow id; the route loads
     *   the envelope from `WorkflowRepository` on entry.
     * @property displayName Cached display name passed through from
     *   the LibraryRow that triggered this nav (so the top bar
     *   doesn't flash empty during the suspending load). The route's
     *   own VM may overwrite this after the load completes.
     */
    data class Graph(
        val workflowId: String,
        val displayName: String,
    ) : AppScreen

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

/**
 * Pure projection: when a [com.comfymobile.domain.workflow.WorkflowRow]
 * is deleted from the library, the host needs to back out of the graph
 * view if it's the one currently being displayed. Cross-screen state
 * leak prevention — same pattern as the old `selectedWorkflow` cleanup
 * that lived here before T2.7 nav wiring.
 *
 * Note: in practice the user can only trigger a delete from Library
 * (Graph has no delete entry point in Phase 2), so they're not in
 * Graph when this fires. But background tabs / process death restore
 * scenarios could still produce this state; keeping the helper makes
 * the invariant explicit.
 */
internal fun shouldPopGraphAfterDelete(
    screen: AppScreen,
    deletedWorkflowId: String,
): Boolean = screen is AppScreen.Graph && screen.workflowId == deletedWorkflowId
