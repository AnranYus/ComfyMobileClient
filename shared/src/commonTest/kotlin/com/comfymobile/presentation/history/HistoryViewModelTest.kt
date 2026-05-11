package com.comfymobile.presentation.history

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.persistence.InMemoryJobRepository
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobStatus
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryViewModelTest {

    @Test fun observes_active_server_history_and_filters_rows() = runTest {
        val repo = InMemoryJobRepository()
        val active = ActiveServerHolder()
        active.setActive(server("srv-A"))
        repo.upsert(job("p-running", status = JobStatus.RUNNING, serverId = "srv-A", createdAt = 2L))
        repo.upsert(job("p-success", status = JobStatus.SUCCEEDED, serverId = "srv-A", createdAt = 1L))
        repo.upsert(job("p-other", status = JobStatus.SUCCEEDED, serverId = "srv-B", createdAt = 3L))
        val vm = viewModel(repo, active, this)

        try {
            runCurrent()
            assertEquals(listOf("p-running", "p-success"), vm.state.value.rows.map { it.promptId })

            vm.onFilterSelected(HistoryFilter.Successful)
            runCurrent()

            assertEquals(listOf("p-success"), vm.state.value.rows.map { it.promptId })
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test fun rename_updates_repository_label_and_clears_dialog() = runTest {
        val repo = InMemoryJobRepository()
        val active = ActiveServerHolder()
        active.setActive(server("srv-A"))
        repo.upsert(job("p-1", status = JobStatus.SUCCEEDED, serverId = "srv-A"))
        val vm = viewModel(repo, active, this)

        try {
            runCurrent()
            vm.onRenameRequested("p-1")
            vm.onRenameValueChanged("New name")
            vm.onConfirmRename()
            runCurrent()

            assertEquals("New name", repo.getByPromptId("p-1")?.label)
            assertEquals(null, vm.state.value.renamePromptId)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test fun delete_removes_row_from_repository() = runTest {
        val repo = InMemoryJobRepository()
        val active = ActiveServerHolder()
        active.setActive(server("srv-A"))
        repo.upsert(job("p-1", status = JobStatus.SUCCEEDED, serverId = "srv-A"))
        val vm = viewModel(repo, active, this)

        try {
            runCurrent()
            vm.onDeleteRequested("p-1")
            vm.onConfirmDelete()
            runCurrent()

            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(null, repo.getByPromptId("p-1"))
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test fun no_active_server_renders_empty_disconnected_state() = runTest {
        val repo = InMemoryJobRepository()
        val active = ActiveServerHolder()
        val vm = viewModel(repo, active, this)

        try {
            runCurrent()
            assertEquals(false, vm.state.value.hasActiveServer)
            assertTrue(vm.state.value.rows.isEmpty())
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    private fun viewModel(
        repo: InMemoryJobRepository,
        active: ActiveServerHolder,
        scope: kotlinx.coroutines.CoroutineScope,
    ): HistoryViewModel = HistoryViewModel(
        repository = repo,
        activeServer = active,
        scope = scope,
        nowEpochMs = { 3_600_000L },
    )

    private fun server(serverId: String): ServerInfo = ServerInfo(
        serverId = serverId,
        host = "192.168.1.10",
        port = 8188,
        label = "Studio",
        lastConnectedAtEpochMs = 1L,
    )

    private fun job(
        promptId: String,
        status: JobStatus,
        serverId: String,
        createdAt: Long = 1L,
    ): Job = Job(
        promptId = promptId,
        serverId = serverId,
        status = status,
        label = promptId,
        apiPromptJson = """{"1":{"inputs":{"text":"prompt for $promptId"}}}""",
        createdAtEpochMs = createdAt,
    )
}
