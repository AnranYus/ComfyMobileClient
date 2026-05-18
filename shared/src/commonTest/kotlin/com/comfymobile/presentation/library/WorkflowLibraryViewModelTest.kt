package com.comfymobile.presentation.library

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.persistence.InMemoryWorkflowRepository
import com.comfymobile.domain.server.ServerInfo
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowLibraryViewModelTest {

    @Test fun observes_persisted_workflows_as_library_rows() = runTest {
        val repository = InMemoryWorkflowRepository()
        val old = repository.upsert(envelope(label = "Old", createdAt = 100L, marker = "old"))
        val new = repository.upsert(envelope(label = "New", createdAt = 200L, marker = "new"))
        val active = ActiveServerHolder()
        active.setActive(server())
        val vm = viewModel(repository, active, backgroundScope)

        runCurrent()

        assertEquals(listOf(new.workflowId, old.workflowId), vm.state.value.rows.map { it.workflowId })
        assertEquals("Studio", vm.state.value.activeServerLabel)
        assertEquals(1, vm.state.value.rows.first().nodeCount)
    }

    @Test fun opening_workflow_marks_recency_and_emits_updated_row() = runTest {
        val repository = InMemoryWorkflowRepository()
        val row = repository.upsert(envelope(label = "Open me", createdAt = 100L))
        val vm = viewModel(repository, scope = backgroundScope)
        val opened = async { vm.openEvents.first() }

        vm.openWorkflow(row.workflowId)
        runCurrent()

        assertEquals(row.workflowId, opened.await().workflowId)
        assertEquals(300L, repository.getById(row.workflowId)?.lastOpenedAtEpochMs)
    }

    @Test fun delete_confirmation_removes_workflow() = runTest {
        val repository = InMemoryWorkflowRepository()
        val row = repository.upsert(envelope(label = "Delete me", createdAt = 100L))
        val vm = viewModel(repository, scope = backgroundScope)

        runCurrent()
        vm.requestDelete(row.workflowId)
        runCurrent()
        assertEquals(row.workflowId, vm.state.value.pendingDelete?.workflowId)

        vm.deleteWorkflow(row.workflowId)
        runCurrent()

        assertNull(repository.getById(row.workflowId))
        assertEquals(null, vm.state.value.pendingDelete)
    }

    @Test fun rename_dialog_keeps_dirty_draft_until_confirm() = runTest {
        val repository = InMemoryWorkflowRepository()
        val row = repository.upsert(envelope(label = "Original", createdAt = 100L))
        val vm = viewModel(repository, scope = backgroundScope)

        runCurrent()
        vm.requestRename(row.workflowId)
        runCurrent()
        assertEquals(row.workflowId, vm.state.value.pendingRename?.workflowId)
        assertEquals("Original", vm.state.value.renameDraft)

        vm.updateRenameDraft("Draft name")
        runCurrent()
        assertEquals("Draft name", vm.state.value.renameDraft)
        assertEquals("Original", repository.getById(row.workflowId)?.displayName)

        vm.dismissRename()
        runCurrent()
        assertNull(vm.state.value.pendingRename)
        assertEquals("", vm.state.value.renameDraft)
        assertEquals("Original", repository.getById(row.workflowId)?.displayName)
    }

    @Test fun confirm_rename_updates_repository_and_clears_dialog() = runTest {
        val repository = InMemoryWorkflowRepository()
        val row = repository.upsert(envelope(label = "Original", createdAt = 100L))
        val vm = viewModel(repository, scope = backgroundScope)

        runCurrent()
        vm.requestRename(row.workflowId)
        vm.updateRenameDraft("  Production workflow  ")
        vm.confirmRename()
        runCurrent()

        assertEquals("Production workflow", repository.getById(row.workflowId)?.displayName)
        assertEquals("Production workflow", repository.getById(row.workflowId)?.envelope?.metadata?.label)
        assertNull(vm.state.value.pendingRename)
        assertEquals("", vm.state.value.renameDraft)
    }

    private fun viewModel(
        repository: InMemoryWorkflowRepository,
        activeServer: ActiveServerHolder = ActiveServerHolder().also { it.setActive(server()) },
        scope: CoroutineScope,
    ) = WorkflowLibraryViewModel(
        repository = repository,
        activeServer = activeServer,
        connectionState = MutableStateFlow(ConnectionState.Connected),
        scope = scope,
        nowEpochMs = { 300L },
    )

    private fun server(): ServerInfo = ServerInfo(
        serverId = "srv-A",
        host = "192.168.1.10",
        port = 8188,
        label = "Studio",
        lastConnectedAtEpochMs = 1L,
    )

    private fun envelope(
        label: String,
        createdAt: Long,
        marker: String = "same",
    ) = WorkflowEnvelope(
        original = buildJsonObject {
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
