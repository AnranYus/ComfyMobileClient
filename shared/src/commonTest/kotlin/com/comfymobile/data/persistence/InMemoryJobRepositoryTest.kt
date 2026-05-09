package com.comfymobile.data.persistence

import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryJobRepositoryTest {

    private fun job(
        promptId: String,
        serverId: String = "srv-A",
        status: JobStatus = JobStatus.QUEUED,
        createdAt: Long = 1000L,
    ) = Job(
        promptId = promptId,
        serverId = serverId,
        status = status,
        createdAtEpochMs = createdAt,
    )

    @Test fun upsert_then_getByPromptId_returns_inserted_row() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1"))
        val out = repo.getByPromptId("p-1")
        assertEquals("p-1", out?.promptId)
        assertEquals(JobStatus.QUEUED, out?.status)
    }

    @Test fun getByPromptId_returns_null_for_unknown_id() = runTest {
        val repo = InMemoryJobRepository()
        assertNull(repo.getByPromptId("missing"))
    }

    @Test fun upsert_replaces_existing_row_with_same_promptId() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1", status = JobStatus.QUEUED))
        repo.upsert(job(promptId = "p-1", status = JobStatus.RUNNING))
        assertEquals(JobStatus.RUNNING, repo.getByPromptId("p-1")?.status)
    }

    @Test fun updateStatus_mutates_only_status_and_finishedAt() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1", status = JobStatus.RUNNING, createdAt = 1000L))
        repo.updateStatus("p-1", JobStatus.SUCCEEDED, finishedAtEpochMs = 1500L)
        val out = repo.getByPromptId("p-1")!!
        assertEquals(JobStatus.SUCCEEDED, out.status)
        assertEquals(1500L, out.finishedAtEpochMs)
        assertEquals(1000L, out.createdAtEpochMs)
    }

    @Test fun updateStatus_silently_no_ops_when_promptId_unknown() = runTest {
        val repo = InMemoryJobRepository()
        // Mirrors the SQL UPDATE-no-row-affected semantics; no throw.
        repo.updateStatus("missing", JobStatus.SUCCEEDED, finishedAtEpochMs = 1L)
        assertNull(repo.getByPromptId("missing"))
    }

    @Test fun listByServer_orders_by_createdAt_descending() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1", createdAt = 1000L))
        repo.upsert(job(promptId = "p-2", createdAt = 3000L))
        repo.upsert(job(promptId = "p-3", createdAt = 2000L))
        val ids = repo.listByServer("srv-A").map { it.promptId }
        assertEquals(listOf("p-2", "p-3", "p-1"), ids)
    }

    @Test fun listByServer_filters_by_serverId() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1", serverId = "srv-A"))
        repo.upsert(job(promptId = "p-2", serverId = "srv-B"))
        assertEquals(listOf("p-1"), repo.listByServer("srv-A").map { it.promptId })
        assertEquals(listOf("p-2"), repo.listByServer("srv-B").map { it.promptId })
    }

    @Test fun listByServer_supports_offset_and_limit() = runTest {
        val repo = InMemoryJobRepository()
        for (i in 1..5) {
            repo.upsert(job(promptId = "p-$i", createdAt = i.toLong()))
        }
        val page = repo.listByServer("srv-A", limit = 2, offset = 1)
        // Sorted desc: p-5, p-4, p-3, p-2, p-1; offset 1 limit 2 → p-4, p-3.
        assertEquals(listOf("p-4", "p-3"), page.map { it.promptId })
    }

    @Test fun listInFlight_returns_only_non_terminal_rows_for_server() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1", status = JobStatus.QUEUED, createdAt = 1L))
        repo.upsert(job(promptId = "p-2", status = JobStatus.RUNNING, createdAt = 2L))
        repo.upsert(job(promptId = "p-3", status = JobStatus.SUCCEEDED, createdAt = 3L))
        repo.upsert(job(promptId = "p-4", status = JobStatus.FAILED, createdAt = 4L))
        repo.upsert(job(promptId = "p-5", status = JobStatus.INTERRUPTED, createdAt = 5L))
        repo.upsert(job(promptId = "p-6", serverId = "srv-B", status = JobStatus.RUNNING, createdAt = 6L))
        val inflight = repo.listInFlight("srv-A").map { it.promptId }.toSet()
        assertEquals(setOf("p-1", "p-2"), inflight)
    }

    @Test fun deleteByServer_removes_rows_for_only_that_server() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1", serverId = "srv-A"))
        repo.upsert(job(promptId = "p-2", serverId = "srv-B"))
        repo.deleteByServer("srv-A")
        assertNull(repo.getByPromptId("p-1"))
        assertEquals("p-2", repo.getByPromptId("p-2")?.promptId)
    }

    @Test fun deleteAll_clears_every_row() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1"))
        repo.upsert(job(promptId = "p-2", serverId = "srv-B"))
        repo.deleteAll()
        assertTrue(repo.listByServer("srv-A").isEmpty())
        assertTrue(repo.listByServer("srv-B").isEmpty())
    }

    @Test fun observeByServer_emits_after_upsert() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job(promptId = "p-1"))
        val first = repo.observeByServer("srv-A").first()
        assertEquals(listOf("p-1"), first.map { it.promptId })
    }
}
