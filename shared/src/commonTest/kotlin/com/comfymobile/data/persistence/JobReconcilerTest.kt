package com.comfymobile.data.persistence

import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Drives [JobReconciler] against [InMemoryJobRepository] + a Ktor
 * MockEngine that hand-picks the response per `prompt_id`. Covers
 * the transitions from CONTEXT v4 / T0.5 G-04: completed / running /
 * not-found / HTTP error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobReconcilerTest {

    private val baseUrl = "http://192.168.1.10:8188"

    private fun http(responder: (promptId: String) -> Pair<HttpStatusCode, String?>): ComfyHttpClient {
        val mock = HttpClient(MockEngine { request ->
            // Path is /history/{promptId}; extract last segment.
            val segments = request.url.encodedPath.split("/").filter { it.isNotEmpty() }
            val promptId = segments.lastOrNull() ?: error("malformed url ${request.url}")
            val (status, body) = responder(promptId)
            respond(
                content = ByteReadChannel(body ?: ""),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return ComfyHttpClient(baseUrl, mock)
    }

    private fun queuedJob(promptId: String, serverId: String = "srv-A"): Job =
        Job(
            promptId = promptId,
            serverId = serverId,
            status = JobStatus.QUEUED,
            createdAtEpochMs = 1000L,
        )

    @Test fun reconcile_promotes_completed_success_inflight_to_SUCCEEDED() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-1"))
        val client = http { _ ->
            HttpStatusCode.OK to """{"p-1":{"status":{"completed":true,"status_str":"success"}}}"""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(1, summary.checked)
        assertEquals(1, summary.settledSucceeded)
        assertEquals(0, summary.settledFailed)
        assertEquals(0, summary.settledInterrupted)
        assertEquals(0, summary.stillRunning)
        assertEquals(0, summary.skippedDueToHttpError)

        val updated = repo.getByPromptId("p-1")!!
        assertEquals(JobStatus.SUCCEEDED, updated.status)
        assertEquals(5000L, updated.finishedAtEpochMs)
    }

    @Test fun reconcile_promotes_completed_error_inflight_to_FAILED() = runTest {
        // Per @Lily PR #9 review (msg `7a630869`): completed = true
        // does NOT necessarily mean success — the server also sets
        // completed = true on error / interrupted prompts and
        // discriminates via status_str.
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-err"))
        val client = http { _ ->
            HttpStatusCode.OK to """{"p-err":{"status":{"completed":true,"status_str":"error"}}}"""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(0, summary.settledSucceeded)
        assertEquals(1, summary.settledFailed)
        assertEquals(JobStatus.FAILED, repo.getByPromptId("p-err")?.status)
    }

    @Test fun reconcile_promotes_completed_interrupted_inflight_to_INTERRUPTED() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-int"))
        val client = http { _ ->
            HttpStatusCode.OK to """{"p-int":{"status":{"completed":true,"status_str":"interrupted"}}}"""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(0, summary.settledSucceeded)
        assertEquals(0, summary.settledFailed)
        assertEquals(1, summary.settledInterrupted)
        assertEquals(JobStatus.INTERRUPTED, repo.getByPromptId("p-int")?.status)
    }

    @Test fun reconcile_keeps_RUNNING_for_inflight_when_server_says_not_completed() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-1"))
        val client = http { _ ->
            HttpStatusCode.OK to """{"p-1":{"status":{"completed":false,"status_str":"running"}}}"""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(0, summary.settledSucceeded)
        assertEquals(0, summary.settledFailed)
        assertEquals(1, summary.stillRunning)
        // Status untouched.
        assertEquals(JobStatus.QUEUED, repo.getByPromptId("p-1")?.status)
    }

    @Test fun reconcile_settles_NotFound_inflight_to_FAILED() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-1"))
        val client = http { _ ->
            HttpStatusCode.NotFound to ""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(0, summary.settledSucceeded)
        assertEquals(1, summary.settledFailed)
        val updated = repo.getByPromptId("p-1")!!
        assertEquals(JobStatus.FAILED, updated.status)
        assertEquals(5000L, updated.finishedAtEpochMs)
    }

    @Test fun reconcile_skips_rows_when_server_returns_5xx() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-1"))
        val client = http { _ ->
            HttpStatusCode.InternalServerError to ""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(1, summary.skippedDueToHttpError)
        assertEquals(0, summary.settledFailed)
        // Status untouched — caller's next reconcile pass will retry.
        assertEquals(JobStatus.QUEUED, repo.getByPromptId("p-1")?.status)
    }

    @Test fun reconcile_only_touches_specified_serverId() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-1", serverId = "srv-A"))
        repo.upsert(queuedJob("p-2", serverId = "srv-B"))
        // Server returns "completed" for any prompt id.
        val client = http { promptId ->
            HttpStatusCode.OK to """{"$promptId":{"status":{"completed":true}}}"""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(1, summary.checked)
        // p-1 (srv-A) was settled; p-2 (srv-B) remains in QUEUED state.
        assertEquals(JobStatus.SUCCEEDED, repo.getByPromptId("p-1")?.status)
        assertEquals(JobStatus.QUEUED, repo.getByPromptId("p-2")?.status)
    }

    @Test fun reconcile_handles_multiple_inflight_with_mixed_outcomes() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-completed", serverId = "srv-A"))
        repo.upsert(queuedJob("p-running", serverId = "srv-A"))
        repo.upsert(queuedJob("p-missing", serverId = "srv-A"))

        val client = http { promptId ->
            when (promptId) {
                "p-completed" ->
                    HttpStatusCode.OK to """{"p-completed":{"status":{"completed":true}}}"""
                "p-running" ->
                    HttpStatusCode.OK to """{"p-running":{"status":{"completed":false}}}"""
                "p-missing" -> HttpStatusCode.NotFound to ""
                else -> error("unexpected promptId: $promptId")
            }
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")

        assertEquals(3, summary.checked)
        assertEquals(1, summary.settledSucceeded)
        assertEquals(1, summary.settledFailed)
        assertEquals(1, summary.stillRunning)

        assertEquals(JobStatus.SUCCEEDED, repo.getByPromptId("p-completed")?.status)
        assertEquals(JobStatus.QUEUED, repo.getByPromptId("p-running")?.status)
        assertEquals(JobStatus.FAILED, repo.getByPromptId("p-missing")?.status)
    }

    @Test fun reconcile_does_not_re_settle_already_terminal_rows() = runTest {
        val repo = InMemoryJobRepository()
        // SUCCEEDED row should not be picked up by listInFlight.
        repo.upsert(
            Job(
                promptId = "p-old",
                serverId = "srv-A",
                status = JobStatus.SUCCEEDED,
                createdAtEpochMs = 1L,
                finishedAtEpochMs = 100L,
            ),
        )
        val client = http { _ ->
            HttpStatusCode.NotFound to ""
        }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")
        assertEquals(0, summary.checked)
        // finished_at preserved (not overwritten by the now-clock).
        assertEquals(100L, repo.getByPromptId("p-old")?.finishedAtEpochMs)
    }

    @Test fun reconcile_propagates_CancellationException_without_swallowing() = runTest {
        // Regression: probeOne must not turn CancellationException
        // into HttpError. If a future refactor reorders or removes
        // the explicit `catch (ce: CancellationException) { throw ce }`,
        // structured concurrency breaks silently — this test pins
        // the behaviour. (Per @Lily PR #9 review msg `cf22e688`.)
        val repo = InMemoryJobRepository()
        repo.upsert(queuedJob("p-1"))
        val client = ComfyHttpClient(
            baseUrl = baseUrl,
            client = HttpClient(MockEngine { _ ->
                throw CancellationException("test cancel")
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        assertFailsWith<CancellationException> {
            reconciler.reconcileServer("srv-A")
        }
        // Repository state untouched — the cancellation interrupted
        // the reconcile before any updateStatus could land.
        assertEquals(JobStatus.QUEUED, repo.getByPromptId("p-1")?.status)
    }

    @Test fun reconcile_with_empty_inflight_returns_zero_summary() = runTest {
        val repo = InMemoryJobRepository()
        val client = http { _ -> HttpStatusCode.OK to """{}""" }
        val reconciler = JobReconciler(http = client, repository = repo, nowEpochMs = { 5000L })
        val summary = reconciler.reconcileServer("srv-A")
        assertEquals(
            JobReconciler.Summary(
                checked = 0,
                settledSucceeded = 0,
                settledFailed = 0,
                settledInterrupted = 0,
                stillRunning = 0,
                skippedDueToHttpError = 0,
            ),
            summary,
        )
    }
}
