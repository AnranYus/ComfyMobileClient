package com.comfymobile.data.persistence

import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.job.JobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure-Kotlin [JobRepository] backed by an in-memory map. Used for:
 *  1. unit tests of the reconciler / connection-state machine without
 *     a real SQLite driver (per @Lily seam pattern), and
 *  2. as a placeholder during early-Phase 1 wiring before the
 *     SQLDelight production implementation lands in T1.4 / T1.3 part 2.
 *
 * Threadsafe via a [Mutex]; observable via [observeByServer]'s
 * snapshot Flow.
 */
class InMemoryJobRepository : JobRepository {

    private val mutex = Mutex()

    /**
     * Backing store, keyed by promptId. We keep a [MutableStateFlow]
     * of the entire map so [observeByServer] can derive its snapshots
     * without requiring callers to refresh manually.
     */
    private val state = MutableStateFlow<Map<String, Job>>(emptyMap())

    override suspend fun upsert(job: Job) {
        mutex.withLock {
            state.value = state.value + (job.promptId to job)
        }
    }

    override suspend fun updateStatus(promptId: String, status: JobStatus, finishedAtEpochMs: Long?) {
        mutex.withLock {
            val current = state.value
            val existing = current[promptId] ?: return
            state.value = current + (promptId to existing.copy(
                status = status,
                finishedAtEpochMs = finishedAtEpochMs,
            ))
        }
    }

    override suspend fun updateLabel(promptId: String, label: String?) {
        mutex.withLock {
            val current = state.value
            val existing = current[promptId] ?: return
            state.value = current + (promptId to existing.copy(label = label))
        }
    }

    override suspend fun updateFirstOutput(promptId: String, firstOutput: JobOutputRef?) {
        mutex.withLock {
            val current = state.value
            val existing = current[promptId] ?: return
            state.value = current + (promptId to existing.copy(firstOutput = firstOutput))
        }
    }

    override suspend fun getByPromptId(promptId: String): Job? = state.value[promptId]

    override suspend fun listByServer(serverId: String, limit: Int, offset: Int): List<Job> =
        snapshotByServer(serverId).drop(offset).take(limit)

    override fun observeByServer(serverId: String, limit: Int, offset: Int): Flow<List<Job>> =
        state.map { sourceState ->
            sourceState.values
                .filter { it.serverId == serverId }
                .sortedByDescending { it.createdAtEpochMs }
                .drop(offset)
                .take(limit)
        }

    override suspend fun listInFlight(serverId: String): List<Job> =
        state.value.values
            .filter { it.serverId == serverId && !it.status.isTerminal }
            .sortedByDescending { it.createdAtEpochMs }

    override suspend fun deleteByServer(serverId: String) {
        mutex.withLock {
            state.value = state.value.filterValues { it.serverId != serverId }
        }
    }

    override suspend fun deleteByPromptId(promptId: String) {
        mutex.withLock {
            state.value = state.value - promptId
        }
    }

    override suspend fun deleteAll() {
        mutex.withLock {
            state.value = emptyMap()
        }
    }

    private fun snapshotByServer(serverId: String): List<Job> =
        state.value.values
            .filter { it.serverId == serverId }
            .sortedByDescending { it.createdAtEpochMs }
}
