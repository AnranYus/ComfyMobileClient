package com.comfymobile.data.persistence

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure-Kotlin workflow repository used by importer tests and early
 * Phase 2 wiring before the SQLDelight implementation lands in T2.6.
 */
class InMemoryWorkflowRepository : WorkflowRepository {

    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, WorkflowRow>>(emptyMap())

    override suspend fun upsert(envelope: WorkflowEnvelope): WorkflowRow = mutex.withLock {
        val workflowId = WorkflowIdentity.workflowIdFor(envelope)
        val existing = state.value[workflowId]
        val row = WorkflowRow(
            workflowId = workflowId,
            displayName = envelope.metadata.label,
            envelope = envelope,
            importedAtEpochMs = existing?.importedAtEpochMs ?: envelope.metadata.createdAtEpochMs,
            lastOpenedAtEpochMs = existing?.lastOpenedAtEpochMs,
        )
        state.value = state.value + (workflowId to row)
        row
    }

    override suspend fun getById(workflowId: String): WorkflowRow? = state.value[workflowId]

    override fun observeAll(): Flow<List<WorkflowRow>> =
        state.map { rows -> rows.values.sortedByRecency() }

    override suspend fun listRecents(limit: Int): List<WorkflowRow> =
        state.value.values.sortedByRecency().take(limit)

    override suspend fun markOpened(workflowId: String, openedAtEpochMs: Long): WorkflowRow? = mutex.withLock {
        val existing = state.value[workflowId] ?: return@withLock null
        val updated = existing.copy(lastOpenedAtEpochMs = openedAtEpochMs)
        state.value = state.value + (workflowId to updated)
        updated
    }

    override suspend fun delete(workflowId: String) = mutex.withLock {
        state.value = state.value - workflowId
    }

    private fun Collection<WorkflowRow>.sortedByRecency(): List<WorkflowRow> =
        sortedWith(
            compareByDescending<WorkflowRow> { it.lastOpenedAtEpochMs ?: it.importedAtEpochMs }
                .thenBy { it.displayName }
                .thenBy { it.workflowId }
        )
}
