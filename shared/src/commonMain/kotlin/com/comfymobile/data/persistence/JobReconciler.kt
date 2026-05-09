package com.comfymobile.data.persistence

import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ComfyHttpException
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.job.JobStatus
import kotlinx.coroutines.CancellationException

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
        val settledInterrupted: Int,
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
        var interrupted = 0
        var stillRunning = 0
        var httpErrors = 0
        for (job in inflight) {
            when (val outcome = probeOne(job)) {
                ProbeOutcome.Succeeded -> {
                    repository.updateStatus(
                        promptId = job.promptId,
                        status = JobStatus.SUCCEEDED,
                        finishedAtEpochMs = nowEpochMs(),
                    )
                    succeeded += 1
                }
                ProbeOutcome.Failed -> {
                    repository.updateStatus(
                        promptId = job.promptId,
                        status = JobStatus.FAILED,
                        finishedAtEpochMs = nowEpochMs(),
                    )
                    failed += 1
                }
                ProbeOutcome.Interrupted -> {
                    repository.updateStatus(
                        promptId = job.promptId,
                        status = JobStatus.INTERRUPTED,
                        finishedAtEpochMs = nowEpochMs(),
                    )
                    interrupted += 1
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
            settledInterrupted = interrupted,
            stillRunning = stillRunning,
            skippedDueToHttpError = httpErrors,
        )
    }

    private suspend fun probeOne(job: Job): ProbeOutcome {
        return try {
            val entry = http.getHistoryEntry(job.promptId)
            when {
                entry == null -> ProbeOutcome.NotFound
                entry.status?.completed != true -> ProbeOutcome.Running
                // completed = true, but we still need status_str to
                // distinguish success / error / interrupted. ComfyUI
                // sets status_str to "success", "error", or
                // "interrupted" on completion — anything else
                // (unknown server build) we conservatively classify
                // as success because completed = true was the
                // dominant signal. Per @Lily PR #9 review msg
                // `7a630869`.
                else -> when (entry.status.status_str) {
                    "success" -> ProbeOutcome.Succeeded
                    "error" -> ProbeOutcome.Failed
                    "interrupted" -> ProbeOutcome.Interrupted
                    null -> ProbeOutcome.Succeeded
                    else -> ProbeOutcome.Succeeded
                }
            }
        } catch (ce: CancellationException) {
            // Never swallow cancellation — that breaks structured
            // concurrency. The reconciler is launched from the runner
            // and the caller relies on cancellation propagating.
            // (Per @Lily PR #9 review msg `7a630869`.)
            throw ce
        } catch (httpEx: ComfyHttpException) {
            // Don't mutate state on transient HTTP errors — caller's
            // next reconcile pass will pick this up. We log nothing
            // here to keep the data layer free of side effects;
            // T1.4 wraps this with structured telemetry.
            ProbeOutcome.HttpError
        } catch (t: Throwable) {
            ProbeOutcome.HttpError
        }
    }

    private enum class ProbeOutcome {
        Succeeded,
        Failed,
        Interrupted,
        Running,
        NotFound,
        HttpError,
    }
}
