package com.comfymobile.domain.workflow

import kotlinx.coroutines.flow.Flow

/**
 * Persistence abstraction for imported workflows.
 *
 * T2.0 writes through this interface from the importer. T2.6 provides
 * the SQLDelight-backed production implementation and the library UI;
 * tests and early wiring use an in-memory implementation.
 */
interface WorkflowRepository {

    /**
     * Insert or replace [envelope], returning the persisted row. The
     * repository owns workflow id generation so import code does not
     * depend on a storage-specific identity strategy.
     */
    suspend fun upsert(envelope: WorkflowEnvelope): WorkflowRow

    /** Lookup a single imported workflow. */
    suspend fun getById(workflowId: String): WorkflowRow?

    /** Observe all imported workflows, most recently opened/imported first. */
    fun observeAll(): Flow<List<WorkflowRow>>

    /** Snapshot recent workflows, most recently opened/imported first. */
    suspend fun listRecents(limit: Int = 50): List<WorkflowRow>

    /** Mark a workflow as opened and return the updated row. */
    suspend fun markOpened(workflowId: String, openedAtEpochMs: Long): WorkflowRow?

    /** Delete one imported workflow. */
    suspend fun delete(workflowId: String)
}

data class WorkflowRow(
    val workflowId: String,
    val displayName: String,
    val envelope: WorkflowEnvelope,
    val importedAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long? = null,
)
