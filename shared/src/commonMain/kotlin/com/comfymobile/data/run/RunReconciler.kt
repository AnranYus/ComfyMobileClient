package com.comfymobile.data.run

import com.comfymobile.data.network.ComfyHttpException
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.data.network.dto.HistoryEntryDto
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.run.ActiveRunContext
import com.comfymobile.domain.run.Clock
import com.comfymobile.domain.run.ReconciledOutcome
import com.comfymobile.domain.run.RunError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Side-channel reconciliation for an in-flight [RunCoordinator] run
 * when the WS event stream is unreliable.
 *
 * Drives the B/C branch behavior specified in T0.4 §3.3 / §3.4 / §3.6
 * + T2.7 §3.4:
 *
 *   - **C branch** (`Reconnecting(BACKGROUND_RESUMED)`): one-shot probe
 *     of `/history/{promptId}` against the run's bound baseUrl. The
 *     run is rebooted from the server's authoritative view: completed
 *     prompts settle into Succeeded/Failed/Cancelled with outputs
 *     extracted from `/history`; still-running prompts are left alone
 *     so the WS reconnect can pick up live events.
 *
 *   - **B branch** (`Reconnecting(LAN_FLAKE)`): periodic probing every
 *     [bBranchPollPeriodMs] (default 3s per T0.4 §3.3) up to a hard
 *     [bBranchTotalTimeoutMs] (default 30s, after which the connection
 *     state will independently transition to `Lost` and the reconciler
 *     stops polling).
 *
 *   - **Connected** / **Lost**: any in-flight probe is cancelled. On
 *     Connected, the normal WS path takes over. On Lost, the user-
 *     facing state machine surfaces the offline banner; the
 *     reconciler stays out of the way.
 *
 * Authority preservation (per @Lily T2.3 follow-up gate 3,
 * msg `39168de4`): a reconciled terminal is delivered through
 * [RunCoordinator.applyReconciledTerminal], which itself checks the
 * coordinator's reducer snapshot — once the reducer has committed a
 * terminal (e.g. from a server WS event that finally arrived),
 * `applyReconciledTerminal` is a no-op. So a /history probe that races
 * a WS terminal cannot override the user-visible outcome.
 *
 * Scope: this reconciler ONLY targets the SINGLE in-flight run owned
 * by [RunCoordinator.activeRunContext]. Settling other pending Job
 * rows belongs to [com.comfymobile.data.persistence.JobReconciler] and
 * is a separate concern.
 */
class RunReconciler(
    /**
     * The active-run context the coordinator publishes. Decoupled from
     * the coordinator class itself so tests can drive the reconciler
     * with a synthetic StateFlow without standing up a real coordinator.
     */
    private val activeRunContext: StateFlow<ActiveRunContext?>,
    /**
     * Deliver a reconciled terminal to the active run. In production
     * this is `RunCoordinator::applyReconciledTerminal`; in tests it's
     * a recorder.
     */
    private val applyReconciledTerminal: suspend (promptId: String, outcome: ReconciledOutcome) -> Boolean,
    private val connectionState: StateFlow<ConnectionState>,
    /**
     * `/history/{prompt_id}` probe seam. Same pattern as the other
     * run-loop ports: takes baseUrl per call so the probe targets the
     * SAME server the run was submitted on, not whatever's currently
     * "active".
     */
    private val historyProbe: HistoryProbePort,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val bBranchPollPeriodMs: Long = 3_000L,
    private val bBranchTotalTimeoutMs: Long = 30_000L,
) {

    private var observerJob: Job? = null
    private var activeProbeJob: Job? = null

    /**
     * Begin observing [connectionState] and launching probes on the
     * appropriate transitions. Idempotent — a second [start] call
     * after a prior [start] without a [stop] is a no-op.
     */
    fun start() {
        if (observerJob != null) return
        observerJob = scope.launch {
            connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Connected -> cancelActiveProbe()
                    is ConnectionState.Reconnecting -> when (state.reason) {
                        ReconnectReason.BACKGROUND_RESUMED -> launchProbeOnce()
                        ReconnectReason.LAN_FLAKE -> launchProbeWithBackoff()
                    }
                    is ConnectionState.Lost -> cancelActiveProbe()
                }
            }
        }
    }

    /** Stop observing and cancel any in-flight probe. */
    suspend fun stop() {
        observerJob?.cancelAndJoin()
        observerJob = null
        activeProbeJob?.cancelAndJoin()
        activeProbeJob = null
    }

    // ----------------------------------------------------------------- probe drivers

    private fun launchProbeOnce() {
        activeProbeJob?.cancel()
        activeProbeJob = scope.launch {
            tryProbeAndApply()
        }
    }

    private fun launchProbeWithBackoff() {
        activeProbeJob?.cancel()
        activeProbeJob = scope.launch {
            val deadlineMs = clock.nowEpochMs() + bBranchTotalTimeoutMs
            while (true) {
                val applied = tryProbeAndApply()
                if (applied) return@launch
                if (clock.nowEpochMs() >= deadlineMs) return@launch
                delay(bBranchPollPeriodMs)
            }
        }
    }

    private fun cancelActiveProbe() {
        activeProbeJob?.cancel()
        activeProbeJob = null
    }

    // ----------------------------------------------------------------- one probe

    /**
     * Probe once. Returns true when a terminal outcome was applied
     * (so the backoff loop can stop), false when:
     *  - No active run.
     *  - Server says the prompt is still running.
     *  - The probe failed with a transient HTTP / network error.
     *  - The coordinator's reducer already had a terminal committed.
     */
    private suspend fun tryProbeAndApply(): Boolean {
        val context = activeRunContext.value ?: return false
        val entry = try {
            historyProbe.getHistoryEntry(context.baseUrl, context.promptId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: ComfyHttpException) {
            return false // transient; backoff loop will retry
        } catch (_: Throwable) {
            return false
        }

        return when (val outcome = classify(entry)) {
            is Classification.StillRunning -> false
            is Classification.Terminal -> {
                applyTerminal(context, outcome.outcome)
                true
            }
            is Classification.NotFound -> {
                // Server's /history has no record. Treat as a definite
                // Failed (per [com.comfymobile.data.persistence.JobReconciler]
                // policy from PR #9 / @Lily review msg `7a630869`).
                applyTerminal(
                    context = context,
                    outcome = ReconciledOutcome.Failed(
                        error = RunError.Network(
                            IllegalStateException("server /history has no entry for ${context.promptId}")
                        )
                    ),
                )
                true
            }
        }
    }

    /**
     * Deliver the reconciled outcome to the coordinator. Persistence
     * is intentionally NOT performed here: the coordinator's
     * `finalizeFromSnapshot` is the single source of truth for
     * JobRepository writes, so the row only changes when the reducer
     * actually commits the reconciliation. Per @Lily PR #34 review
     * msg `4996df44` blocker 2: a stale/missing /history outcome must
     * NOT overwrite a Job row already settled by a real WS terminal.
     */
    private suspend fun applyTerminal(
        context: ActiveRunContext,
        outcome: ReconciledOutcome,
    ) {
        applyReconciledTerminal(context.promptId, outcome)
    }

    // ----------------------------------------------------------------- classification

    private fun classify(entry: HistoryEntryDto?): Classification {
        if (entry == null) return Classification.NotFound
        val completed = entry.status?.completed == true
        if (!completed) return Classification.StillRunning
        val statusStr = entry.status?.status_str
        val outcome = when (statusStr) {
            "success", null -> ReconciledOutcome.Succeeded(
                outputs = extractAllImageOutputs(entry.outputs),
            )
            "error" -> ReconciledOutcome.Failed(
                error = RunError.Network(
                    IllegalStateException("server reported execution_error for this prompt"),
                ),
            )
            "interrupted" -> ReconciledOutcome.Interrupted
            else -> ReconciledOutcome.Succeeded(
                outputs = extractAllImageOutputs(entry.outputs),
            )
        }
        return Classification.Terminal(outcome)
    }

    /**
     * `/history.outputs` is a per-node map `{nodeId: {images: [...], gifs: [...], ...}}`.
     * Walk every node and extract image refs across `images` and `gifs`,
     * preserving discovery order so the gallery shows them in execution
     * order (SaveImage nodes typically run last).
     */
    private fun extractAllImageOutputs(outputs: kotlinx.serialization.json.JsonElement?): List<JobOutputRef> {
        val map = outputs as? JsonObject ?: return emptyList()
        val result = mutableListOf<JobOutputRef>()
        for ((_, nodeOutputs) in map) {
            val nodeObj = nodeOutputs as? JsonObject ?: continue
            for (key in listOf("images", "gifs")) {
                val arr = nodeObj[key] as? JsonArray ?: continue
                for (item in arr) {
                    val obj = item as? JsonObject ?: continue
                    val filename = (obj["filename"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val subfolder = (obj["subfolder"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: JobOutputRef.TYPE_OUTPUT
                    result.add(JobOutputRef(filename = filename, subfolder = subfolder, type = type))
                }
            }
        }
        return result
    }

    // ----------------------------------------------------------------- types

    private sealed interface Classification {
        data object StillRunning : Classification
        data object NotFound : Classification
        data class Terminal(val outcome: ReconciledOutcome) : Classification
    }
}

/**
 * `/history/{prompt_id}` probe seam. Production adapter wraps
 * `ComfyHttpClient.getHistoryEntry`. Tests substitute a fake.
 *
 * The probe takes `baseUrl` per call (rather than reading from a
 * holder) so it can always target the SAME server the run was
 * submitted on — same pattern as the other run-loop ports.
 */
fun interface HistoryProbePort {
    suspend fun getHistoryEntry(baseUrl: String, promptId: String): HistoryEntryDto?
}

