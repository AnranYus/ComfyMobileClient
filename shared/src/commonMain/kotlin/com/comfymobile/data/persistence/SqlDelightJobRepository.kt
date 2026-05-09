package com.comfymobile.data.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.comfymobile.db.ComfyMobileDb
import com.comfymobile.db.JobIndex
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.job.JobStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed [JobRepository] used in production.
 *
 * The platform-specific SqlDriver (`AndroidSqliteDriver` /
 * `NativeSqliteDriver`) lives in T1.4's DI module — this class only
 * sees the resulting [ComfyMobileDb] instance, so it remains in
 * commonMain and can be exercised by `androidUnitTest` /
 * `iosTest` against a JdbcSqliteDriver / NativeSqliteDriver instance.
 *
 * All suspending operations are dispatched on [ioDispatcher] so the
 * caller (typically a Compose ViewModel) doesn't block the main
 * thread on a synchronous SQLite call.
 */
class SqlDelightJobRepository(
    private val db: ComfyMobileDb,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : JobRepository {

    private val queries get() = db.jobQueries

    override suspend fun upsert(job: Job) = withContext(ioDispatcher) {
        // The .sq file declares this as `INSERT OR REPLACE`, so a
        // duplicate prompt_id (e.g. resubmitted after a transient WS
        // drop) doesn't collide on the PRIMARY KEY. (Per @Lily PR #9
        // review msg `7a630869`.)
        queries.upsertJob(
            prompt_id = job.promptId,
            server_id = job.serverId,
            status = job.status.wireValue,
            workflow_snapshot_json = job.workflowSnapshotJson,
            api_prompt_json = job.apiPromptJson,
            label = job.label,
            created_at = job.createdAtEpochMs,
            finished_at = job.finishedAtEpochMs,
        )
    }

    override suspend fun updateStatus(
        promptId: String,
        status: JobStatus,
        finishedAtEpochMs: Long?,
    ) = withContext(ioDispatcher) {
        queries.updateStatus(
            status = status.wireValue,
            finished_at = finishedAtEpochMs,
            prompt_id = promptId,
        )
    }

    override suspend fun getByPromptId(promptId: String): Job? = withContext(ioDispatcher) {
        queries.selectByPromptId(promptId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun listByServer(serverId: String, limit: Int, offset: Int): List<Job> =
        withContext(ioDispatcher) {
            queries.selectByServer(
                server_id = serverId,
                limit = limit.toLong(),
                offset = offset.toLong(),
            ).executeAsList().map({ row -> row.toDomain() })
        }

    override fun observeByServer(serverId: String, limit: Int, offset: Int): Flow<List<Job>> =
        queries.selectByServer(
            server_id = serverId,
            limit = limit.toLong(),
            offset = offset.toLong(),
        )
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map({ row -> row.toDomain() }) }
            .flowOn(ioDispatcher)

    override suspend fun listInFlight(serverId: String): List<Job> = withContext(ioDispatcher) {
        queries.selectInflight(serverId).executeAsList().map({ row -> row.toDomain() })
    }

    override suspend fun deleteByServer(serverId: String) = withContext(ioDispatcher) {
        queries.deleteByServer(serverId)
    }

    override suspend fun deleteAll() = withContext(ioDispatcher) {
        queries.deleteAll()
    }

    private fun JobIndex.toDomain(): Job = Job(
        promptId = prompt_id,
        serverId = server_id,
        status = JobStatus.fromWire(status),
        workflowSnapshotJson = workflow_snapshot_json,
        apiPromptJson = api_prompt_json,
        label = label,
        createdAtEpochMs = created_at,
        finishedAtEpochMs = finished_at,
    )
}
