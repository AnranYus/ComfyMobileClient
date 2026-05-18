package com.comfymobile.presentation.workflow

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText
import com.comfymobile.presentation.graph.GestureState
import com.comfymobile.presentation.graph.LayoutResult
import com.comfymobile.presentation.graph.ParsedUiGraph

/**
 * UI state for [WorkflowGraphRoute] per @Ores T2.7 §1.10.
 *
 * Strictly serializable / pure-data (no Compose / no Flow handles) so
 * the route can be unit-tested without spinning a UI.
 *
 * @property workflowId The id passed in via [WorkflowGraphViewModel.load].
 *   `null` until the first load() call.
 * @property displayName Human-readable workflow label from
 *   [com.comfymobile.domain.workflow.WorkflowRow.displayName]. Empty
 *   until the row resolves.
 * @property envelope The current workflow envelope. Updated when
 *   ParamEditor applies — i.e. this is *not* always equal to the row
 *   that was originally loaded. `null` until load resolves.
 * @property parsedGraph Render-layer projection of [envelope]. `null`
 *   when the envelope is API-format (no positions / links).
 * @property layoutResult Auto-layout output paired with [parsedGraph].
 *   `null` whenever [parsedGraph] is null.
 * @property gestureState Single source of truth for pan / zoom /
 *   selection. The view dispatches gesture intents that the VM applies
 *   via `GestureReducer.reduce`.
 * @property selectedNodeId Currently-selected node id from
 *   [gestureState]; mirrored here so the view doesn't have to read
 *   nested gesture state.
 * @property pendingLongPress Latest long-press hit that the view has
 *   not yet consumed. The view's `LaunchedEffect` on this field calls
 *   [com.comfymobile.presentation.parameditor.ParamEditorViewModel.open]
 *   then [WorkflowGraphViewModel.onConsumePendingLongPress]. We use a
 *   monotonically increasing seq inside the value so that two
 *   long-presses on the same node both trigger the open (the
 *   composition's `LaunchedEffect(key=value)` would otherwise see
 *   "same key, no rerun").
 * @property pendingTapReopen Latest tap that hit the already-selected
 *   node (i.e. user tapped a selected node a second time, per §2.1
 *   second trigger). Same opaque-seq pattern as [pendingLongPress].
 * @property canRun Mirrors the spec acceptance contract:
 *   `ConnectionState == Connected && envelope != null && envelope.isSubmittable`.
 *   The view binds the Run FAB enabled-state to this.
 * @property errorMessage Loading / parsing / not-found surface — null
 *   means no error. Distinct from the param editor's own error path
 *   which lives in `ParamEditorScreenState`.
 * @property isLoading True while the workflow envelope is being loaded
 *   from [com.comfymobile.domain.workflow.WorkflowRepository]. Drives
 *   the loading placeholder; cleared once load resolves (regardless of
 *   success / not-found).
 * @property firstFrameHintVisible True on first render of a given
 *   workflowId until the user dismisses (§1.9 onboarding).
 */
data class WorkflowGraphScreenState(
    val workflowId: String? = null,
    val displayName: String = "",
    val envelope: WorkflowEnvelope? = null,
    val parsedGraph: ParsedUiGraph? = null,
    val layoutResult: LayoutResult? = null,
    val gestureState: GestureState = GestureState.Identity,
    val selectedNodeId: String? = null,
    val pendingLongPress: PendingNodeEvent? = null,
    val pendingTapReopen: PendingNodeEvent? = null,
    val canRun: Boolean = false,
    val errorMessage: LocalizedText? = null,
    val isLoading: Boolean = false,
    val firstFrameHintVisible: Boolean = false,
    val language: ConnectionLanguage = ConnectionLanguage.En,
)

/**
 * One-shot node event the route consumes via a LaunchedEffect. The
 * [seq] is monotonically increasing so two consecutive events with the
 * same `nodeId` produce distinct values (otherwise Compose's
 * `LaunchedEffect(key=value)` would skip the second run because the
 * key didn't change).
 */
data class PendingNodeEvent(val nodeId: String, val seq: Long)
