package com.comfymobile.presentation.run

import com.comfymobile.data.network.ConnectionState
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.run.RunError
import com.comfymobile.domain.run.RunState
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.presentation.graph.NodeRuntimeStatus
import kotlinx.serialization.json.JsonElement

/**
 * Display-layer projection consumed by `RunScreen`. Derived purely from
 * three inputs:
 *   - [com.comfymobile.domain.run.RunCoordinator.state] (the run lifecycle)
 *   - [com.comfymobile.data.network.ConnectionState] (A/B/C banner concern)
 *   - the "prepared workflow + active server" pair (CTA enablement)
 *
 * Construction is unidirectional and pure — the projection never feeds
 * back into [RunState] (per @Lily T2.3 second-segment gate 3,
 * msg `3e9e7269`).
 *
 * The B/C connection banner ([branchBanner]) is layered ON TOP of the
 * run lifecycle: a `Reconnecting` ConnectionState while the run is in
 * `Running` shows a yellow "reconnecting" banner — but the run terminal
 * (Succeeded/Failed/Cancelled) is ALWAYS authoritative (gate 2). The
 * banner never overrides a terminal result.
 */
data class RunUiState(
    /** What the top status row should render. Drives the cancel-CTA visibility too. */
    val phase: Phase,

    /** Optional secondary annotation pinned above the top status row (B/C only). */
    val branchBanner: BranchBanner = BranchBanner.None,

    /** Run CTA enable condition (gate 1). */
    val canSubmit: Boolean,

    /** Cancel CTA enable condition: true when run is Queued or Running. */
    val canCancel: Boolean,

    /** When true, the destructive confirm sheet is open. */
    val cancelConfirmOpen: Boolean = false,

    /** Per-node graph annotation (consumed by `GraphCanvas`). */
    val runtimeStatusByNode: Map<String, NodeRuntimeStatus> = emptyMap(),

    /** Most recent thumb to show in the bottom detail card. */
    val lastOutputThumbnail: JobOutputRef? = null,

    /** Surfaced when the run terminates failed/cancelled, until dismissed. */
    val terminal: TerminalView? = null,
) {
    sealed interface Phase {
        /** No workflow prepared, or freshly entered the route. Run CTA may be enabled. */
        data class Idle(val workflowTitle: String?) : Phase

        /** `POST /prompt` is in flight. Non-cancellable. */
        data object Submitting : Phase

        /** Queued — cancel routes to `/queue {"delete":[id]}` via coordinator. */
        data class Queued(
            val promptId: String,
            val queuePosition: Int,
        ) : Phase

        /** Running — cancel routes to `/interrupt` via coordinator. */
        data class Running(
            val promptId: String,
            val currentNodeId: String?,
            val currentNodeDisplayName: String?,
            val progress: NodeProgressView?,
        ) : Phase

        /** Terminal success — top row collapses; gallery transition is owned by the host. */
        data class Succeeded(
            val promptId: String,
            val outputs: List<JobOutputRef>,
        ) : Phase

        /** Terminal failure — error sheet drives off [TerminalView]. */
        data class Failed(
            val promptId: String?,
        ) : Phase

        /** Terminal cancelled — toast / dismiss; bottom CTA goes back to "再次运行". */
        data class Cancelled(
            val promptId: String,
        ) : Phase
    }

    data class NodeProgressView(
        val nodeId: String,
        val value: Int,
        val max: Int,
    )

    /**
     * Trust-layer banner per T0.4 §3.3 / §3.4 / §3.5. The renderer picks
     * the copy and color; the projection only labels the kind.
     *
     * - [None]: A-branch (steady WS).
     * - [Reconnecting]: B-branch (LAN flake) — silent breathing dot.
     * - [BackgroundResuming]: C-branch (background resumed) — explicit
     *   orange banner.
     * - [Offline]: Lost terminal — red banner with retry CTA.
     */
    sealed interface BranchBanner {
        data object None : BranchBanner
        data object Reconnecting : BranchBanner
        data object BackgroundResuming : BranchBanner
        data class Offline(val reason: ConnectionState.Lost) : BranchBanner
    }

    /**
     * Render payload for terminal Failed/Cancelled sheets. Succeeded
     * transitions don't use this (the host navigates straight to the
     * gallery).
     */
    sealed interface TerminalView {
        data class Failure(
            val promptId: String?,
            val title: String,
            val message: String,
            val failingNodeDisplayName: String? = null,
            val tracebackJson: JsonElement? = null,
        ) : TerminalView
        data class Cancelled(val promptId: String) : TerminalView
    }
}

/**
 * Submission carrier the UI hands to [RunViewModel.onSubmit]. Wrapping
 * the workflow + optional `/object_info` cache here lets the host
 * supply them at submit time without the VM coupling to a workflow
 * repository or `/object_info` cache directly.
 */
data class PreparedWorkflow(
    val envelope: WorkflowEnvelope,
    /** Optional `/object_info` payload for [com.comfymobile.domain.workflow.WorkflowConverter]. */
    val objectInfo: JsonElement? = null,
    /** Optional user-visible label persisted on the [com.comfymobile.domain.job.Job] row. */
    val label: String? = null,
)
