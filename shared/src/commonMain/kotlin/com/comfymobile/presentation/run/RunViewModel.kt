package com.comfymobile.presentation.run

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.domain.run.RunCoordinator
import com.comfymobile.domain.run.RunState
import com.comfymobile.domain.run.RunSubmission
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowGraph
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.StateFlow as KStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * UI-layer driver for one [RunCoordinator] instance.
 *
 * Lifecycle:
 *  - Constructed per-screen (factory binding in `AppModule`), receives
 *    a `vmScope` from the host so collection is bound to the screen.
 *  - The host calls [prepare] with the workflow it wants to submit.
 *  - The host wires [uiState] into Compose for the surface.
 *  - The host invokes [onSubmit] / [requestCancel] / [confirmCancel] /
 *    [dismissCancel] / [dismissTerminal] in response to user gestures.
 *
 * **Boundaries** (per @Lily T2.3 second-segment gates, msg `3e9e7269`):
 *  - Gate 1: [uiState].canSubmit is true only when a workflow has been
 *    prepared, an active server is set, AND the coordinator is in a
 *    submitable state (Idle / terminal).
 *  - Gate 2: The B/C connection-state banner is derived from
 *    [ConnectionState] alone in [RunUiStateMapper]. The run terminal
 *    is always authoritative; the banner can co-exist but never
 *    suppresses a [RunState.Succeeded] / `.Failed` / `.Cancelled`.
 *  - Gate 3: [RunUiState] is a pure projection; this VM never reads
 *    UI state back into the coordinator.
 *  - Gate 4: [requestCancel] calls [RunCoordinator.requestCancel] only.
 *    The coordinator owns the running/queued protocol split; the VM
 *    does not pick endpoints.
 */
class RunViewModel(
    private val coordinator: RunCoordinator,
    private val activeServer: ActiveServerHolder,
    private val connectionState: KStateFlow<ConnectionState>,
    private val clientId: String,
    private val scope: CoroutineScope,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {

    /** Workflow the user has loaded; null until [prepare]. */
    private val preparedWorkflow = MutableStateFlow<PreparedWorkflow?>(null)

    /** UI-only local state: the destructive cancel sheet. */
    private val cancelConfirmOpen = MutableStateFlow(false)

    /**
     * UI-only local state: the *key* of the terminal the user has
     * dismissed. The mapper suppresses the terminal sheet only when
     * the current run state's terminal key matches this value.
     *
     * Key derivation (see [terminalKey]):
     *  - Failed / Cancelled with a promptId → the promptId.
     *  - Failed with promptId = null (submission-time failure) → a
     *    fixed sentinel so the dismissed flag still survives.
     *  - Non-terminal states → null (no key to compare against).
     *
     * Reset semantics (per @Lily PR #31 second-round review msg
     * `8bbd4fa1` blocker 2): the dismissal is keyed, not boolean. So
     * `prepare()` does NOT touch this — a user who dismisses a Failed
     * sheet then loads another workflow sees no stale resurrection,
     * and a future failure with a DIFFERENT promptId surfaces fresh
     * because keys won't match. `onSubmit()` clears the key explicitly
     * to cover the corner case where the state moves through the
     * brief Submitting phase before settling on a new terminal (the
     * dismissed key is then meaningless).
     */
    private val dismissedTerminalKey = MutableStateFlow<String?>(null)

    /**
     * Lookup table from numeric node id (as it appears in the UI graph)
     * to its human-readable `displayName`. Filled by [prepare] from
     * the workflow's UI JSON so the surface can show "KSampler" instead
     * of "5". Best-effort: missing entries fall back to the node id.
     */
    private var nodeDisplayNames: Map<String, String> = emptyMap()

    val uiState: StateFlow<RunUiState> =
        combine(
            listOf(
                coordinator.state,
                connectionState,
                preparedWorkflow,
                activeServer.current,
                cancelConfirmOpen,
                dismissedTerminalKey,
            )
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val run = values[0] as RunState
            @Suppress("UNCHECKED_CAST")
            val conn = values[1] as ConnectionState
            @Suppress("UNCHECKED_CAST")
            val prepared = values[2] as PreparedWorkflow?
            @Suppress("UNCHECKED_CAST")
            val server = values[3] as com.comfymobile.domain.server.ServerInfo?
            val confirm = values[4] as Boolean
            @Suppress("UNCHECKED_CAST")
            val dismissedKey = values[5] as String?
            // Suppress only when the CURRENT terminal matches the
            // dismissed key — so an old dismissed key doesn't hide a
            // newer different-promptId terminal.
            val terminalDismissed = run.terminalKey() != null
                && run.terminalKey() == dismissedKey
            RunUiStateMapper.project(
                runState = run,
                connectionState = conn,
                // Only UI-form workflows are submittable today; API-only
                // envelopes would silently no-op in onSubmit so the CTA
                // must reflect that (@Lily PR #31 blocker 4).
                hasPreparedWorkflow = prepared != null && prepared.envelope.isSubmittable,
                hasActiveServer = server != null,
                cancelConfirmOpen = confirm,
                workflowTitle = prepared?.label,
                nodeDisplayNameByNodeId = { id -> nodeDisplayNames[id] },
                language = language,
                terminalDismissed = terminalDismissed,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = RunUiStateMapper.project(
                runState = RunState.Idle,
                connectionState = connectionState.value,
                hasPreparedWorkflow = false,
                hasActiveServer = activeServer.current.value != null,
                language = language,
            ),
        )

    // ----------------------------------------------------------------- intents

    /**
     * Stage a workflow for submission. Replaces any previously prepared
     * workflow. Does NOT auto-submit; the user must explicitly tap Run.
     *
     * Side-effect: rebuilds [nodeDisplayNames] from the envelope's UI
     * graph so the surface can show readable node names in the bottom
     * detail card and error sheet.
     */
    fun prepare(prepared: PreparedWorkflow) {
        preparedWorkflow.value = prepared
        nodeDisplayNames = buildNodeDisplayNameIndex(prepared.envelope)
        // Do NOT reset dismissedTerminalKey here (@Lily PR #31 second-
        // round blocker 2): if the user dismissed a Failed("p-1") sheet
        // and then loaded another workflow, the prior failure sheet
        // must STAY hidden — the dismissed key is matched per-promptId
        // so a future Failed("p-2") with a different key surfaces fresh
        // on its own.
    }

    /**
     * Submit the prepared workflow.
     *
     * No-op when no workflow is prepared, no active server is set, or
     * the coordinator already has a run in flight. The CTA in the
     * surface should mirror these gates via [RunUiState.canSubmit]; the
     * check here is defense in depth.
     */
    fun onSubmit() {
        val prepared = preparedWorkflow.value ?: return
        val server = activeServer.current.value ?: return
        val runState = coordinator.state.value
        if (!canSubmitNow(runState)) return

        val ui = prepared.envelope.ui ?: return
        // Clear the dismissed-terminal key — a fresh submit moves us
        // out of any prior terminal state into Submitting, so any old
        // terminal sheet that was visually dismissed no longer matters.
        dismissedTerminalKey.value = null
        // Pin the run to THIS server's baseUrl. If the user switches
        // active server mid-run, the run continues against the snapshot
        // captured here — never gets redirected.
        val submission = RunSubmission(
            serverId = server.serverId,
            baseUrl = server.baseUrl,
            clientId = clientId,
            workflowId = prepared.workflowId,
            workflowUi = ui,
            objectInfo = prepared.objectInfo,
            workflowSnapshotJson = prepared.envelope.uiJsonOrNull(),
            label = prepared.label,
            // Embed the current UI snapshot so generated PNGs roundtrip
            // back to the SAME workflow (ADR-0003 + Lily T0.6 bug catch).
            extraData = prepared.envelope.uiSnapshotForExtraData(),
        )
        scope.launch {
            coordinator.run(submission)
        }
    }

    /** Open the destructive cancel-confirm sheet. */
    fun requestCancel() {
        if (coordinator.state.value.let { it is RunState.Queued || it is RunState.Running }) {
            cancelConfirmOpen.value = true
        }
    }

    /** User confirmed cancel — delegate to the coordinator's protocol-split routing. */
    fun confirmCancel() {
        cancelConfirmOpen.value = false
        scope.launch {
            coordinator.requestCancel()
        }
    }

    /** User dismissed the cancel-confirm sheet without confirming. */
    fun dismissCancel() {
        cancelConfirmOpen.value = false
    }

    /**
     * Hide the surfaced terminal sheet for the CURRENT run terminal.
     *
     * The underlying [RunState] remains terminal — it only changes on
     * the next submit — but the surface stops rendering the modal
     * sheet so the user can return to the workflow view. The dismissal
     * is *keyed* to the specific terminal's promptId so:
     *   - Loading another workflow after dismissal does NOT resurrect
     *     this sheet (the key stays matched).
     *   - A FUTURE run that terminates with a DIFFERENT promptId
     *     surfaces fresh because the new terminal's key won't match.
     *
     * Per @Lily PR #31 review msg `18946cd9` blocker 1 (sheet couldn't
     * close at all) + msg `8bbd4fa1` blocker 2 (boolean reset on
     * prepare resurrected the dismissed sheet — keyed by promptId now).
     */
    fun dismissTerminal() {
        val key = coordinator.state.value.terminalKey() ?: return
        dismissedTerminalKey.value = key
    }

    // ----------------------------------------------------------------- helpers

    private fun canSubmitNow(runState: RunState): Boolean = when (runState) {
        is RunState.Idle,
        is RunState.Succeeded,
        is RunState.Failed,
        is RunState.Cancelled -> true
        is RunState.Submitting,
        is RunState.Queued,
        is RunState.Running -> false
    }

    /**
     * Walk the workflow's UI JSON `nodes` array and build a
     * `nodeId → displayName` map.
     *
     * The UI form uses integer node ids while the API form (and the
     * coordinator's state machine) carry them as decimal strings.
     * `displayName` may live under the node's `title` (user-overridden)
     * or fall back to its `type` (canonical class name).
     */
    private fun buildNodeDisplayNameIndex(envelope: WorkflowEnvelope): Map<String, String> {
        val ui = envelope.ui ?: return emptyMap()
        val raw = ui.raw
        val nodes = (raw["nodes"] as? JsonArray) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (node in nodes) {
            val obj = node as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.content ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val type = (obj["type"] as? JsonPrimitive)?.content
            val name = title ?: type ?: continue
            out[id] = name
        }
        return out
    }
}

/**
 * Stable identity for a terminal-state instance, used as the key for
 * the "I dismissed THIS sheet" flag. Returns null for non-terminal
 * states (the suppression check then never matches).
 *
 * Failed-at-submit (`promptId = null`) collapses to a single sentinel
 * so the user can still dismiss it. A subsequent failed-at-submit
 * shares the same key, so two back-to-back submit failures would only
 * surface the sheet once — acceptable for now; the user can always
 * navigate to retry.
 */
internal fun RunState.terminalKey(): String? = when (this) {
    is RunState.Failed -> promptId ?: SUBMIT_FAIL_KEY
    is RunState.Cancelled -> promptId
    is RunState.Succeeded -> null // No dismissable sheet; the surface transitions away.
    is RunState.Idle,
    is RunState.Submitting,
    is RunState.Queued,
    is RunState.Running -> null
}

private const val SUBMIT_FAIL_KEY = "<submit-fail>"

/**
 * Best-effort access to the UI form of a [WorkflowEnvelope].
 *
 * The envelope's `original` JSON is structure-lossless; we wrap it in a
 * [WorkflowGraph.Ui] only when [WorkflowFormat.UI] (the form
 * [com.comfymobile.domain.workflow.WorkflowConverter.uiToApi] accepts).
 * API-only envelopes return null — the run flow can't submit them
 * without converting first, which is out of scope here.
 */
internal val WorkflowEnvelope.ui: WorkflowGraph.Ui?
    get() = when (format) {
        WorkflowFormat.UI -> (original as? JsonObject)?.let { WorkflowGraph.Ui(it) }
        WorkflowFormat.API -> null
    }

/**
 * True when this envelope can be submitted directly by [RunCoordinator].
 * Today only UI-format envelopes are submittable (API-only ones would
 * need an upstream conversion step we haven't built yet). Drives the
 * `canSubmit` gate in [RunUiState] so the CTA isn't enabled for an
 * envelope `onSubmit` would silently no-op on (@Lily PR #31 blocker 4).
 */
internal val WorkflowEnvelope.isSubmittable: Boolean
    get() = ui != null

/**
 * Re-serialise the UI snapshot to a JSON string for [com.comfymobile.domain.job.Job.workflowSnapshotJson].
 * Returns null when no UI form is available — the job row will simply
 * lack the "reopen workflow" affordance for runs submitted from an
 * API-only envelope.
 */
private fun WorkflowEnvelope.uiJsonOrNull(): String? =
    ui?.raw?.toString()

/**
 * Build the `extra_data` payload that embeds the current UI snapshot in
 * the generated PNG's `extra_pnginfo.workflow` field, so re-import via
 * "drop PNG" roundtrips to the exact workflow that produced it.
 *
 * Per ADR-0003 + @Lily T0.6 bug catch: this MUST be the *current* edited
 * UI snapshot, not the originally-imported one.
 */
private fun WorkflowEnvelope.uiSnapshotForExtraData(): JsonElement? {
    val raw = ui?.raw ?: return null
    return buildJsonObject {
        put("extra_pnginfo", buildJsonObject {
            put("workflow", raw)
        })
    }
}
