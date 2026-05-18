package com.comfymobile.data.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.comfymobile.db.ComfyMobileDb
import com.comfymobile.db.Workflow
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class SqlDelightWorkflowRepository(
    private val db: ComfyMobileDb,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : WorkflowRepository {

    private val queries get() = db.workflowQueries

    override suspend fun upsert(envelope: WorkflowEnvelope): WorkflowRow = withContext(ioDispatcher) {
        val workflowId = WorkflowIdentity.workflowIdFor(envelope)
        val existing = queries.selectWorkflowById(workflowId).executeAsOneOrNull()
        val importedAt = existing?.imported_at ?: envelope.metadata.createdAtEpochMs
        val lastOpenedAt = existing?.last_opened_at
        queries.upsertWorkflow(
            id = workflowId,
            friendly_name = envelope.metadata.label,
            format = envelope.format.name,
            original_json = json.encodeToString(JsonElement.serializer(), envelope.original),
            metadata_json = json.encodeToString(envelope.metadata),
            imported_at = importedAt,
            last_opened_at = lastOpenedAt,
            last_run_prompt_id = existing?.last_run_prompt_id,
        )
        queries.selectWorkflowById(workflowId).executeAsOne().toDomain()
    }

    override suspend fun getById(workflowId: String): WorkflowRow? = withContext(ioDispatcher) {
        queries.selectWorkflowById(workflowId).executeAsOneOrNull()?.toDomain()
    }

    override fun observeAll(): Flow<List<WorkflowRow>> =
        queries.selectAllWorkflows()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun listRecents(limit: Int): List<WorkflowRow> = withContext(ioDispatcher) {
        queries.selectRecentWorkflows(limit = limit.toLong())
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun markOpened(workflowId: String, openedAtEpochMs: Long): WorkflowRow? =
        withContext(ioDispatcher) {
            queries.markWorkflowOpened(
                last_opened_at = openedAtEpochMs,
                id = workflowId,
            )
            queries.selectWorkflowById(workflowId).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun rename(workflowId: String, displayName: String): WorkflowRow? =
        withContext(ioDispatcher) {
            queries.renameWorkflow(
                friendly_name = displayName,
                id = workflowId,
            )
            queries.selectWorkflowById(workflowId).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun delete(workflowId: String) = withContext(ioDispatcher) {
        queries.deleteWorkflow(workflowId)
    }

    private fun Workflow.toDomain(): WorkflowRow {
        val metadata = json.decodeFromString<WorkflowMetadata>(metadata_json)
        val envelope = WorkflowEnvelope(
            original = json.decodeFromString(JsonElement.serializer(), original_json),
            format = WorkflowFormat.valueOf(format),
            metadata = metadata.copy(label = friendly_name),
        )
        return WorkflowRow(
            workflowId = id,
            displayName = friendly_name,
            envelope = envelope,
            importedAtEpochMs = imported_at,
            lastOpenedAtEpochMs = last_opened_at,
        )
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
