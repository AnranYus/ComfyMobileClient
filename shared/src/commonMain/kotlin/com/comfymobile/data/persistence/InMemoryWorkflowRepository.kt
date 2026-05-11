package com.comfymobile.data.persistence

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pure-Kotlin workflow repository used by importer tests and early
 * Phase 2 wiring before the SQLDelight implementation lands in T2.6.
 */
class InMemoryWorkflowRepository : WorkflowRepository {

    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, WorkflowRow>>(emptyMap())

    override suspend fun upsert(envelope: WorkflowEnvelope): WorkflowRow = mutex.withLock {
        val workflowId = workflowIdFor(envelope)
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

    override suspend fun delete(workflowId: String) = mutex.withLock {
        state.value = state.value - workflowId
    }

    private fun Collection<WorkflowRow>.sortedByRecency(): List<WorkflowRow> =
        sortedWith(
            compareByDescending<WorkflowRow> { it.lastOpenedAtEpochMs ?: it.importedAtEpochMs }
                .thenBy { it.displayName }
                .thenBy { it.workflowId }
        )

    private fun workflowIdFor(envelope: WorkflowEnvelope): String {
        val canonical = envelope.format.name + ":" + json.encodeToString(
            JsonElement.serializer(),
            canonicalize(envelope.original),
        )
        return "wf_" + fnv1a64(canonical.encodeToByteArray()).toULong().toString(radix = 36)
    }

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .sortedBy { it.key }
                    .associate { (key, value) -> key to canonicalize(value) }
            )
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = FNV_64_OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= FNV_64_PRIME
        }
        return hash
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
        const val FNV_64_OFFSET_BASIS: Long = -3750763034362895579L
        const val FNV_64_PRIME: Long = 1099511628211L
    }
}
