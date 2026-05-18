package com.comfymobile.presentation.workflow

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowGraph
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.graph.GestureIntent
import com.comfymobile.presentation.graph.GestureReducer
import com.comfymobile.presentation.graph.GestureState
import com.comfymobile.presentation.graph.GraphLayout
import com.comfymobile.presentation.graph.UiGraphParser
import com.comfymobile.presentation.run.isSubmittable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * ViewModel for [WorkflowGraphRoute] per @Ores T2.7 §1.10.
 *
 * Responsibilities:
 *  1. Load a [com.comfymobile.domain.workflow.WorkflowRow] from
 *     [WorkflowRepository] when the route opens (§1.10 entry from
 *     LibraryRoute).
 *  2. Parse the envelope into a [com.comfymobile.presentation.graph.ParsedUiGraph]
 *     and lay it out so the canvas has a stable, deterministic plan
 *     source.
 *  3. Own the [GestureState] for pan / zoom / selection, applying it
 *     via the pure [GestureReducer]. The canvas dispatches
 *     [GestureIntent] values; the route forwards them here and the VM
 *     publishes the new state in [state].
 *  4. Intercept [GestureIntent.Tap] and [GestureIntent.LongPress] so
 *     the route can surface §2.1 trigger semantics (long-press → open
 *     drawer; tap-on-already-selected → re-open drawer). These are
 *     exposed as one-shot [PendingNodeEvent] fields the route's
 *     `LaunchedEffect` consumes (no event lost on configuration
 *     change because LaunchedEffect keys on the value, and our seq
 *     bumps strictly monotonically).
 *  5. Accept envelope updates from ParamEditor (`onEnvelopeApplied`)
 *     and re-derive parsed graph + layout in one place — the canvas
 *     always renders against the most recent envelope.
 *
 * Per @Lily PR #25 contract (relayed in spec §1.10): the
 * [ParamEditorViewModel] this route hosts is page-scoped, NOT
 * `APP_SCOPE`. That's the caller's responsibility — DI binds
 * `ParamEditorViewModel` as a factory keyed on `vmScope`.
 *
 * The VM does NOT own connection state. The route observes
 * `ConnectionState` directly (same pattern as [com.comfymobile.presentation.run.RunRoute])
 * and feeds the `connected` boolean into [setConnected] so the Run FAB
 * `canRun` derives correctly. Keeping the ConnectionState observation
 * out of the VM keeps the unit tests free of state-machine setup.
 */
class WorkflowGraphViewModel(
    private val repository: WorkflowRepository,
    private val scope: CoroutineScope,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val mutableState = MutableStateFlow(WorkflowGraphScreenState(language = language))
    val state: StateFlow<WorkflowGraphScreenState> = mutableState.asStateFlow()

    private var longPressSeq: Long = 0L
    private var tapReopenSeq: Long = 0L
    private var connected: Boolean = false
    private var loadGeneration: Long = 0L

    // ---------------------------------------------------------------- load

    /**
     * Load the workflow with [workflowId] from the repository. Sets
     * `isLoading = true` immediately, then on completion either:
     *  - Fills in the envelope + parsed graph + layout (UI-format), OR
     *  - Surfaces an API-format-unsupported error (envelope set, parsed
     *    graph null), OR
     *  - Surfaces a not-found error (envelope null, error set).
     *
     * Subsequent calls with a new workflowId cancel the in-flight load
     * via [loadGeneration] check — only the latest call wins.
     */
    fun load(workflowId: String) {
        loadGeneration += 1
        val generation = loadGeneration
        mutableState.value = WorkflowGraphScreenState(
            workflowId = workflowId,
            isLoading = true,
            firstFrameHintVisible = true,
            language = language,
        )
        scope.launch {
            val row = repository.getById(workflowId)
            // Stale-generation guard: a newer load() may have arrived
            // while we were awaiting the suspend call.
            if (generation != loadGeneration) return@launch
            if (row == null) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    errorMessage = WorkflowGraphCopy.workflowNotFound,
                    firstFrameHintVisible = false,
                )
                return@launch
            }
            applyLoadedEnvelope(row.displayName, row.envelope)
        }
    }

    /**
     * For the import-success entry (§1.10): caller already has the
     * envelope in hand from the importer and doesn't need to round
     * trip through repository.getById. Skips the suspend load entirely.
     */
    fun loadFromEnvelope(workflowId: String, displayName: String, envelope: WorkflowEnvelope) {
        loadGeneration += 1
        mutableState.value = WorkflowGraphScreenState(
            workflowId = workflowId,
            displayName = displayName,
            isLoading = false,
            firstFrameHintVisible = true,
            language = language,
        )
        applyLoadedEnvelope(displayName, envelope)
    }

    private fun applyLoadedEnvelope(displayName: String, envelope: WorkflowEnvelope) {
        when (envelope.format) {
            WorkflowFormat.UI -> {
                val original = envelope.original as? JsonObject
                if (original == null) {
                    mutableState.value = mutableState.value.copy(
                        displayName = displayName,
                        envelope = envelope,
                        parsedGraph = null,
                        layoutResult = null,
                        isLoading = false,
                        errorMessage = WorkflowGraphCopy.apiFormatUnsupported,
                        canRun = connected && envelope.isSubmittable,
                    )
                    return
                }
                val parsed = UiGraphParser.parse(WorkflowGraph.Ui(original))
                val layout = GraphLayout.layout(parsed)
                mutableState.value = mutableState.value.copy(
                    displayName = displayName,
                    envelope = envelope,
                    parsedGraph = parsed,
                    layoutResult = layout,
                    isLoading = false,
                    errorMessage = null,
                    canRun = connected && envelope.isSubmittable,
                )
            }
            WorkflowFormat.API -> {
                // API format has no UI metadata (positions / links /
                // node titles); the canvas needs those to render. We
                // still allow Run on the envelope itself — that path
                // doesn't go through this route's FAB anyway when the
                // graph can't render.
                mutableState.value = mutableState.value.copy(
                    displayName = displayName,
                    envelope = envelope,
                    parsedGraph = null,
                    layoutResult = null,
                    isLoading = false,
                    errorMessage = WorkflowGraphCopy.apiFormatUnsupported,
                    canRun = connected && envelope.isSubmittable,
                )
            }
        }
    }

    // ---------------------------------------------------------------- gesture wiring

    /**
     * Forward a [GestureIntent] from [com.comfymobile.presentation.graph.InteractiveGraphCanvas]
     * into the reducer, and additionally intercept [GestureIntent.Tap]
     * / [GestureIntent.LongPress] so the route can surface §2.1
     * trigger semantics.
     */
    fun onIntent(intent: GestureIntent) {
        val current = mutableState.value
        val previousSelected = current.gestureState.selectedNodeId

        when (intent) {
            is GestureIntent.LongPress -> {
                // Apply the reducer first so selection state updates
                // (the canvas considers a long-press a select for
                // visual feedback).
                val nextGesture = GestureReducer.reduce(current.gestureState, intent)
                val pending = intent.hitNodeId?.let { nodeId ->
                    PendingNodeEvent(nodeId = nodeId, seq = ++longPressSeq)
                }
                mutableState.value = current.copy(
                    gestureState = nextGesture,
                    selectedNodeId = nextGesture.selectedNodeId,
                    pendingLongPress = pending ?: current.pendingLongPress,
                )
            }
            is GestureIntent.Tap -> {
                val nextGesture = GestureReducer.reduce(current.gestureState, intent)
                // Per §2.1 second trigger: tapping an already-selected
                // node re-opens the drawer for that node. Detect via
                // the *previous* selectedNodeId because the reducer
                // has already mutated it to the tapped node id.
                val reopen = intent.hitNodeId
                    ?.takeIf { it == previousSelected }
                    ?.let { PendingNodeEvent(nodeId = it, seq = ++tapReopenSeq) }
                mutableState.value = current.copy(
                    gestureState = nextGesture,
                    selectedNodeId = nextGesture.selectedNodeId,
                    pendingTapReopen = reopen ?: current.pendingTapReopen,
                )
            }
            else -> {
                val nextGesture = GestureReducer.reduce(current.gestureState, intent)
                mutableState.value = current.copy(
                    gestureState = nextGesture,
                    selectedNodeId = nextGesture.selectedNodeId,
                )
            }
        }
    }

    /** Mark the latest long-press as handled; clears the one-shot. */
    fun onConsumePendingLongPress() {
        mutableState.value = mutableState.value.copy(pendingLongPress = null)
    }

    /** Mark the latest tap-on-already-selected as handled; clears the one-shot. */
    fun onConsumePendingTapReopen() {
        mutableState.value = mutableState.value.copy(pendingTapReopen = null)
    }

    // ---------------------------------------------------------------- envelope updates

    /**
     * The hosted [com.comfymobile.presentation.parameditor.ParamEditorViewModel]
     * surfaced an applied envelope via its `lastAppliedEnvelope`. The
     * route forwards it here so we re-derive parsed graph + layout +
     * `canRun` against the new envelope. Selection / pan / zoom are
     * preserved (user expectation: applying a param doesn't reset
     * their view).
     */
    fun onEnvelopeApplied(envelope: WorkflowEnvelope) {
        val current = mutableState.value
        when (envelope.format) {
            WorkflowFormat.UI -> {
                val original = envelope.original as? JsonObject ?: return
                val parsed = UiGraphParser.parse(WorkflowGraph.Ui(original))
                val layout = GraphLayout.layout(parsed)
                mutableState.value = current.copy(
                    envelope = envelope,
                    parsedGraph = parsed,
                    layoutResult = layout,
                    canRun = connected && envelope.isSubmittable,
                    errorMessage = null,
                )
            }
            WorkflowFormat.API -> {
                mutableState.value = current.copy(
                    envelope = envelope,
                    canRun = connected && envelope.isSubmittable,
                )
            }
        }
    }

    // ---------------------------------------------------------------- connection feed

    /**
     * The route observes `ConnectionState.isConnected` (or equivalent)
     * and pumps the boolean here so [WorkflowGraphScreenState.canRun]
     * stays current with the active server's readiness. Kept out of
     * the VM so unit tests don't need the connection state machine.
     */
    fun setConnected(connected: Boolean) {
        this.connected = connected
        val envelope = mutableState.value.envelope ?: run {
            mutableState.value = mutableState.value.copy(canRun = false)
            return
        }
        mutableState.value = mutableState.value.copy(
            canRun = connected && envelope.isSubmittable,
        )
    }

    /** Dismisses the first-frame onboarding tooltip; one-shot per session. */
    fun onDismissFirstFrameHint() {
        mutableState.value = mutableState.value.copy(firstFrameHintVisible = false)
    }
}
