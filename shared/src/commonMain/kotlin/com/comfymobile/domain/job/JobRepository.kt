package com.comfymobile.domain.job

import kotlinx.coroutines.flow.Flow

/**
 * Persistence abstraction for the local job index. The production
 * implementation in `data/persistence/SqlDelightJobRepository` wraps
 * the SQLDelight-generated queries; tests substitute an in-memory
 * fake (per @Lily seam — every IO surface in the data layer needs to
 * be replaceable by a stub).
 */
interface JobRepository {

    /**
     * Persist a freshly-submitted job. Caller has already received a
     * `prompt_id` from `POST /prompt`; on conflict the existing row
     * is replaced (idempotent retry-safe).
     */
    suspend fun upsert(job: Job)

    /** Update only the status / finished_at of an existing row. */
    suspend fun updateStatus(promptId: String, status: JobStatus, finishedAtEpochMs: Long?)

    /** Update the user-visible label for a history row. */
    suspend fun updateLabel(promptId: String, label: String?)

    /** Update the first output ref used by history thumbnails. */
    suspend fun updateFirstOutput(promptId: String, firstOutput: JobOutputRef?)

    /** Single-row lookup by primary key. */
    suspend fun getByPromptId(promptId: String): Job?

    /**
     * Paginated history list for a given server, most-recent first.
     * Backed by the `(server_id, created_at DESC)` index.
     */
    suspend fun listByServer(serverId: String, limit: Int = 50, offset: Int = 0): List<Job>

    /**
     * Stream history list as a Flow so the UI can observe new
     * insertions without re-querying. SQLDelight's coroutines
     * extensions emit on every commit affecting the underlying query.
     */
    fun observeByServer(serverId: String, limit: Int = 50, offset: Int = 0): Flow<List<Job>>

    /**
     * All non-terminal rows for a given server. The reconciler calls
     * this on B/C reconnect to find rows that may have ghost
     * `running` / `queued` state while the client was offline.
     */
    suspend fun listInFlight(serverId: String): List<Job>

    /** Wipe every job for a server (used when removing a server entry). */
    suspend fun deleteByServer(serverId: String)

    /** Delete one job row from history. */
    suspend fun deleteByPromptId(promptId: String)

    /** Wipe every job (used when clearing app data). */
    suspend fun deleteAll()
}
