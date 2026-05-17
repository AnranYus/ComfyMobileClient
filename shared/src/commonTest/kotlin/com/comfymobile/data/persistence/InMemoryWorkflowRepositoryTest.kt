package com.comfymobile.data.persistence

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryWorkflowRepositoryTest {

    @Test fun upsert_returns_stable_id_for_same_original_workflow() = runTest {
        val repository = InMemoryWorkflowRepository()
        val envelope = envelope(label = "First", createdAt = 100L)

        val first = repository.upsert(envelope)
        val second = repository.upsert(envelope.copy(
            metadata = envelope.metadata.copy(label = "Renamed", createdAtEpochMs = 200L),
        ))

        assertEquals(first.workflowId, second.workflowId)
        assertEquals("Renamed", repository.getById(first.workflowId)?.displayName)
        assertEquals(100L, repository.getById(first.workflowId)?.importedAtEpochMs)
    }

    @Test fun workflow_id_is_stable_when_json_object_key_order_changes() = runTest {
        val repository = InMemoryWorkflowRepository()

        val first = repository.upsert(envelope(
            label = "First",
            createdAt = 100L,
            original = buildJsonObject {
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(1))
                        put("type", JsonPrimitive("KSampler"))
                    })
                })
                put("links", buildJsonArray {})
            },
        ))
        val second = repository.upsert(envelope(
            label = "Second",
            createdAt = 200L,
            original = buildJsonObject {
                put("links", buildJsonArray {})
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("KSampler"))
                        put("id", JsonPrimitive(1))
                    })
                })
            },
        ))

        assertEquals(first.workflowId, second.workflowId)
        assertEquals("Second", repository.getById(first.workflowId)?.displayName)
    }

    @Test fun observeAll_and_listRecents_sort_by_import_time_descending() = runTest {
        val repository = InMemoryWorkflowRepository()
        val old = repository.upsert(envelope(label = "Old", createdAt = 100L, marker = "old"))
        val new = repository.upsert(envelope(label = "New", createdAt = 200L, marker = "new"))

        assertEquals(
            listOf(new.workflowId, old.workflowId),
            repository.listRecents().map { it.workflowId },
        )
        assertEquals(
            listOf(new.workflowId, old.workflowId),
            repository.observeAll().first().map { it.workflowId },
        )
    }

    @Test fun markOpened_updates_recency_without_changing_import_time() = runTest {
        val repository = InMemoryWorkflowRepository()
        val old = repository.upsert(envelope(label = "Old", createdAt = 100L, marker = "old"))
        val new = repository.upsert(envelope(label = "New", createdAt = 200L, marker = "new"))

        val opened = repository.markOpened(old.workflowId, openedAtEpochMs = 300L)

        assertEquals(old.importedAtEpochMs, opened?.importedAtEpochMs)
        assertEquals(300L, opened?.lastOpenedAtEpochMs)
        assertEquals(
            listOf(old.workflowId, new.workflowId),
            repository.listRecents().map { it.workflowId },
        )
    }

    @Test fun delete_removes_workflow() = runTest {
        val repository = InMemoryWorkflowRepository()
        val row = repository.upsert(envelope(label = "Delete me", createdAt = 100L))

        repository.delete(row.workflowId)

        assertNull(repository.getById(row.workflowId))
        assertEquals(emptyList(), repository.listRecents())
    }

    private fun envelope(
        label: String,
        createdAt: Long,
        marker: String = "same",
        original: JsonObject? = null,
    ) = WorkflowEnvelope(
        original = original ?: buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive(1))
                    put("type", JsonPrimitive("KSampler"))
                    put("marker", JsonPrimitive(marker))
                })
            })
        },
        format = WorkflowFormat.UI,
        metadata = WorkflowMetadata(
            label = label,
            createdAtEpochMs = createdAt,
            lastEditedAtEpochMs = createdAt,
        ),
    )
}
