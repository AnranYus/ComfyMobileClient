package com.comfymobile.data.persistence

import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ComfyHttpException
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.job.JobStatus

/**
 * Settles in-flight rows in [JobRepository] against the server's
 * `/history/{prompt_id}` view. Used after a B/C reconnect (per
 * ADR-0004 §3 / T0.5 G-04 / G-05) so a ghost `running` / `queued`
 * row is never displayed to the user.
 *
 * Pure data flow:
 *   1. Read all in-flight rows for the active server.
 *   2. For each, ask `/history/{prompt_id}`:
 *      - 200 with `status.completed = true` → SUCCEEDED
 *      - 200 with completed = false / null   → still RUNNING
 *      - 404 (or null body)                  → server forgot the
 *        prompt; we map to FAILED with the timestamp now (caller
 *        provides a clock)
 *      - HTTP 5xx / network error            → leave the row alone;
 *        next reconnect will retry
 *   3. Update repository rows whose terminal state changed.
 *
 * Returns a [Result] summary so the caller (T1.4 ViewModel) can
 * surface "X jobs settled" telemetry / banner if desired.
 */
class JobReconciler(
    private val http: ComfyHttpClient,
    private val repository: JobRepository,
    /** Wall-clock provider so tests can pin reconciliation timestamps. */
    private val nowEpochMs: () -> Long,
) {

    data class Summary(
        val checked: Int,
        val settledSucceeded: Int,
        val settledFailed: Int,
        val stillRunning: Int,
        val skippedDueToHttpError: Int,
    )

    /**
     * Reconcile every in-flight row against the live server. The
     * caller passes the [serverId] so reconciliation only touches
     * rows for the connection currently in scope.
     */
    suspend fun reconcileServer(serverId: String): Summary {
        val inflight = repository.listInFlight(serverId)
        var succeeded = 0
        var failed = 0
        var stillRunning = 0
        var httpErrors = 0
        for (job in inflight) {
            when (val outcome = probeOne(job)) {
                ProbeOutcome.Completed -> {
                    repository.updateStatus(
                        promptId = job.promptId,
                        status = JobStatus.SUCCEEDED,
                        finishedAtEpochMs = nowEpochMs(),
                    )
                    succeeded += 1
                }
                ProbeOutcome.NotFound -> {
                    // Server has no record of this prompt — most likely
                    // it was pruned (server `/history` is bounded). Mark
                    // failed so the user sees a definite outcome rather
                    // than a permanent ghost.
                    repository.updateStatus(
                        promptId = job.promptId,
                        status = JobStatus.FAILED,
                        finishedAtEpochMs = nowEpochMs(),
                    )
                    failed += 1
                }
                ProbeOutcome.Running -> stillRunning += 1
                ProbeOutcome.HttpError -> httpErrors += 1
            }
        }
        return Summary(
            checked = inflight.size,
            settledSucceeded = succeeded,
            settledFailed = failed,
            stillRunning = stillRunning,
            skippedDueToHttpError = httpErrors,
        )
    }

    private suspend fun probeOne(job: Job): ProbeOutcome =
        try {
            val entry = http.getHistoryEntry(job.promptId)
            when {
                entry == null -> ProbeOutcome.NotFound
                entry.status?.completed == true -> ProbeOutcome.Completed
                else -> ProbeOutcome.Running
            }
        } catch (httpEx: ComfyHttpException) {
            // Don't mutate state on transient HTTP errors — caller's
            // next reconcile pass will pick this up. We log nothing
            // here to keep the data layer free of side effects;
            // T1.4 wraps this with structured telemetry.
            ProbeOutcome.HttpError
        } catch (t: Throwable) {
            ProbeOutcome.HttpError
        }

    private enum class ProbeOutcome {
        Completed,
        Running,
        NotFound,
        HttpError,
    }
}
