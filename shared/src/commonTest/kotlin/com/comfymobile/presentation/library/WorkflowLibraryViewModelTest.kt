package com.comfymobile.presentation.library

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.persistence.InMemoryJobRepository
import com.comfymobile.data.persistence.InMemoryWorkflowRepository
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobStatus
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
        val vm = viewModel(repository, activeServer = active, scope = backgroundScope)

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

    @Test fun thumbnail_uses_latest_succeeded_job_first_output_for_matching_workflow() = runTest {
        val repository = InMemoryWorkflowRepository()
        val jobs = InMemoryJobRepository()
        val row = repository.upsert(envelope(label = "Thumb", createdAt = 100L))
        jobs.upsert(job(
            promptId = "old",
            workflowId = row.workflowId,
            workflowSnapshotJson = row.envelope.original.toString(),
            firstOutput = JobOutputRef("old.png"),
            createdAt = 100L,
            finishedAt = 120L,
        ))
        jobs.upsert(job(
            promptId = "new",
            workflowId = row.workflowId,
            workflowSnapshotJson = row.envelope.original.toString(),
            firstOutput = JobOutputRef("new.png", subfolder = "runs"),
            createdAt = 200L,
            finishedAt = 240L,
        ))
        jobs.upsert(job(
            promptId = "failed-newer",
            status = JobStatus.FAILED,
            workflowId = row.workflowId,
            workflowSnapshotJson = row.envelope.original.toString(),
            firstOutput = JobOutputRef("failed.png"),
            createdAt = 300L,
            finishedAt = 330L,
        ))
        jobs.upsert(job(
            promptId = "other",
            workflowId = "other-workflow",
            workflowSnapshotJson = """{"nodes":[]}""",
            firstOutput = JobOutputRef("other.png"),
            createdAt = 400L,
            finishedAt = 450L,
        ))
        val vm = viewModel(
            repository = repository,
            jobRepository = jobs,
            scope = backgroundScope,
            thumbnailUrlForOutput = { output -> "thumb:${output.subfolder}/${output.filename}" },
        )

        runCurrent()

        assertEquals("thumb:runs/new.png", vm.state.value.rows.single().thumbnailUrl)
    }

    @Test fun thumbnail_uses_workflowId_when_edited_snapshot_differs_from_persisted_workflow_json() = runTest {
        val repository = InMemoryWorkflowRepository()
        val jobs = InMemoryJobRepository()
        val row = repository.upsert(envelope(label = "Edited", createdAt = 100L, marker = "persisted"))
        jobs.upsert(job(
            promptId = "edited-run",
            workflowId = row.workflowId,
            // Main path: Library → Graph → ParamEditor mutates the
            // envelope before Run. The job snapshot is the edited UI
            // JSON, so it no longer equals the persisted library row.
            workflowSnapshotJson = envelope(label = "Edited", createdAt = 100L, marker = "edited").original.toString(),
            firstOutput = JobOutputRef("edited.png", subfolder = "runs"),
            createdAt = 200L,
            finishedAt = 240L,
        ))
        val vm = viewModel(
            repository = repository,
            jobRepository = jobs,
            scope = backgroundScope,
            thumbnailUrlForOutput = { output -> "thumb:${output.subfolder}/${output.filename}" },
        )

        runCurrent()

        assertEquals("thumb:runs/edited.png", vm.state.value.rows.single().thumbnailUrl)
    }

    @Test fun thumbnail_does_not_cross_servers_for_same_workflowId() = runTest {
        val repository = InMemoryWorkflowRepository()
        val jobs = InMemoryJobRepository()
        val active = ActiveServerHolder()
        active.setActive(server(serverId = "srv-A", label = "Studio A"))
        val row = repository.upsert(envelope(label = "Same workflow", createdAt = 100L))
        jobs.upsert(job(
            promptId = "a-run",
            serverId = "srv-A",
            workflowId = row.workflowId,
            workflowSnapshotJson = row.envelope.original.toString(),
            firstOutput = JobOutputRef("a.png", subfolder = "runs"),
            createdAt = 100L,
            finishedAt = 120L,
        ))
        jobs.upsert(job(
            promptId = "b-run",
            serverId = "srv-B",
            workflowId = row.workflowId,
            workflowSnapshotJson = row.envelope.original.toString(),
            firstOutput = JobOutputRef("b.png", subfolder = "runs"),
            createdAt = 200L,
            finishedAt = 240L,
        ))
        val vm = viewModel(
            repository = repository,
            jobRepository = jobs,
            activeServer = active,
            scope = backgroundScope,
            thumbnailUrlForOutput = { output -> "thumb:${output.filename}" },
        )

        runCurrent()
        assertEquals("thumb:a.png", vm.state.value.rows.single().thumbnailUrl)

        active.setActive(server(serverId = "srv-B", label = "Studio B"))
        runCurrent()
        assertEquals("thumb:b.png", vm.state.value.rows.single().thumbnailUrl)
    }

    @Test fun thumbnail_falls_back_when_no_succeeded_output_matches_workflow() = runTest {
        val repository = InMemoryWorkflowRepository()
        val jobs = InMemoryJobRepository()
        val row = repository.upsert(envelope(label = "Fallback", createdAt = 100L))
        jobs.upsert(job(
            promptId = "same-but-no-output",
            workflowId = row.workflowId,
            workflowSnapshotJson = row.envelope.original.toString(),
            firstOutput = null,
            createdAt = 100L,
            finishedAt = 120L,
        ))
        val vm = viewModel(
            repository = repository,
            jobRepository = jobs,
            scope = backgroundScope,
            thumbnailUrlForOutput = { output -> "thumb:${output.filename}" },
        )

        runCurrent()

        assertNull(vm.state.value.rows.single().thumbnailUrl)
    }

    private fun viewModel(
        repository: InMemoryWorkflowRepository,
        jobRepository: InMemoryJobRepository = InMemoryJobRepository(),
        activeServer: ActiveServerHolder = ActiveServerHolder().also { it.setActive(server()) },
        scope: CoroutineScope,
        thumbnailUrlForOutput: (JobOutputRef) -> String? = { null },
    ) = WorkflowLibraryViewModel(
        repository = repository,
        jobRepository = jobRepository,
        activeServer = activeServer,
        connectionState = MutableStateFlow(ConnectionState.Connected),
        scope = scope,
        nowEpochMs = { 300L },
        thumbnailUrlForOutput = thumbnailUrlForOutput,
    )

    private fun server(
        serverId: String = "srv-A",
        label: String = "Studio",
    ): ServerInfo = ServerInfo(
        serverId = serverId,
        host = "192.168.1.10",
        port = 8188,
        label = label,
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

    private fun job(
        promptId: String,
        serverId: String = "srv-A",
        workflowId: String? = null,
        workflowSnapshotJson: String?,
        firstOutput: JobOutputRef?,
        status: JobStatus = JobStatus.SUCCEEDED,
        createdAt: Long,
        finishedAt: Long?,
    ): Job = Job(
        promptId = promptId,
        serverId = serverId,
        workflowId = workflowId,
        status = status,
        workflowSnapshotJson = workflowSnapshotJson,
        firstOutput = firstOutput,
        createdAtEpochMs = createdAt,
        finishedAtEpochMs = finishedAt,
    )
}
