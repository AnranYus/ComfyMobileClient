package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.persistence.InMemoryJobRepository
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.job.JobStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputGalleryViewModelTest {

    @Test fun show_single_output_opens_viewer_immediately() {
        val vm = viewModel()

        vm.show(outputs = listOf(ComfyOutputRef("one.png", "", "output")), title = "Job")

        assertEquals("Job", vm.state.value.title)
        assertEquals(1, vm.state.value.items.size)
        assertEquals(0, vm.state.value.selectedIndex)
        assertEquals("http://server/view?filename=one.png&subfolder=&type=output&preview=jpeg%3B90", vm.state.value.items.single().imageUrl)
    }

    @Test fun show_multiple_outputs_starts_in_grid() {
        val vm = viewModel()

        vm.show(
            outputs = listOf(
                ComfyOutputRef("one.png", "", "output"),
                ComfyOutputRef("two.png", "", "output"),
            ),
        )

        assertEquals(2, vm.state.value.items.size)
        assertNull(vm.state.value.selectedIndex)

        vm.openItem(1)
        assertEquals(1, vm.state.value.selectedIndex)
    }

    @Test fun batch_selection_toggles_by_index() {
        val vm = viewModel()
        vm.show(
            outputs = listOf(
                ComfyOutputRef("one.png", "", "output"),
                ComfyOutputRef("two.png", "", "output"),
            ),
        )

        vm.toggleBatchSelection(0)
        assertTrue(0 in vm.state.value.selectedForBatch)
        vm.toggleBatchSelection(0)
        assertTrue(vm.state.value.selectedForBatch.isEmpty())
    }

    @Test fun favorite_toggles_job_repository_when_prompt_id_is_bound() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job("prompt-1"))
        val vm = viewModel(repository = repo, scope = this)

        vm.show(
            outputs = listOf(ComfyOutputRef("one.png", "", "output")),
            promptId = "prompt-1",
            isFavorite = false,
        )

        assertTrue(vm.state.value.favoriteEnabled)
        vm.actions().onToggleFavorite()
        runCurrent()

        assertEquals(true, repo.getByPromptId("prompt-1")?.isFavorite)
        assertEquals(true, vm.state.value.isFavorite)
        assertEquals(null, vm.state.value.actionInProgress)
    }

    @Test fun favorite_stays_disabled_without_prompt_id() = runTest {
        val repo = InMemoryJobRepository()
        val vm = viewModel(repository = repo, scope = this)

        vm.show(outputs = listOf(ComfyOutputRef("one.png", "", "output")))

        assertEquals(false, vm.state.value.favoriteEnabled)
    }

    @Test fun stale_favorite_result_does_not_mutate_next_gallery_session() = runTest {
        val repo = DelayedFavoriteRepository()
        repo.upsert(job("prompt-a"))
        repo.upsert(job("prompt-b"))
        val vm = viewModel(repository = repo, scope = this)

        vm.show(
            outputs = listOf(ComfyOutputRef("a.png", "", "output")),
            promptId = "prompt-a",
            isFavorite = false,
        )
        vm.actions().onToggleFavorite()
        repo.updateStarted.await()

        vm.show(
            outputs = listOf(ComfyOutputRef("b.png", "", "output")),
            promptId = "prompt-b",
            isFavorite = false,
        )
        repo.allowUpdate.complete(Unit)
        runCurrent()

        assertEquals(true, repo.getByPromptId("prompt-a")?.isFavorite)
        assertEquals(false, repo.getByPromptId("prompt-b")?.isFavorite)
        assertEquals("prompt-b", vm.state.value.promptId)
        assertEquals(false, vm.state.value.isFavorite)
        assertEquals(null, vm.state.value.actionInProgress)
    }

    @Test fun save_and_share_use_injected_action_gateway_only_when_supported() = runTest {
        val gateway = RecordingActionGateway()
        val vm = viewModel(actionGateway = gateway, scope = this)

        vm.show(outputs = listOf(ComfyOutputRef("one.png", "", "output")))

        assertTrue(vm.state.value.saveEnabled)
        assertTrue(vm.state.value.shareEnabled)
        vm.actions().onSaveSelected()
        runCurrent()
        vm.actions().onShareSelected()
        runCurrent()

        assertEquals(listOf("save:one.png", "share:one.png"), gateway.calls)
    }

    private fun viewModel(
        repository: JobRepository? = null,
        actionGateway: OutputGalleryActionGateway = DisabledOutputGalleryActionGateway,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): OutputGalleryViewModel =
        OutputGalleryViewModel(
            imageMapper = ComfyImageMapper(activeBaseUrlProvider = { "http://server" }),
            jobRepository = repository,
            actionGateway = actionGateway,
            scope = scope,
        )

    private fun job(promptId: String): Job = Job(
        promptId = promptId,
        serverId = "srv-A",
        status = JobStatus.SUCCEEDED,
        createdAtEpochMs = 1L,
    )

    private class DelayedFavoriteRepository : JobRepository {
        private val delegate = InMemoryJobRepository()
        val updateStarted = CompletableDeferred<Unit>()
        val allowUpdate = CompletableDeferred<Unit>()

        override suspend fun upsert(job: Job) {
            delegate.upsert(job)
        }

        override suspend fun updateStatus(promptId: String, status: JobStatus, finishedAtEpochMs: Long?) {
            delegate.updateStatus(promptId, status, finishedAtEpochMs)
        }

        override suspend fun updateLabel(promptId: String, label: String?) {
            delegate.updateLabel(promptId, label)
        }

        override suspend fun updateFirstOutput(promptId: String, firstOutput: JobOutputRef?) {
            delegate.updateFirstOutput(promptId, firstOutput)
        }

        override suspend fun updateFavorite(promptId: String, isFavorite: Boolean) {
            updateStarted.complete(Unit)
            allowUpdate.await()
            delegate.updateFavorite(promptId, isFavorite)
        }

        override suspend fun getByPromptId(promptId: String): Job? =
            delegate.getByPromptId(promptId)

        override suspend fun listByServer(serverId: String, limit: Int, offset: Int): List<Job> =
            delegate.listByServer(serverId, limit, offset)

        override fun observeByServer(serverId: String, limit: Int, offset: Int): Flow<List<Job>> =
            delegate.observeByServer(serverId, limit, offset)

        override suspend fun listInFlight(serverId: String): List<Job> =
            delegate.listInFlight(serverId)

        override suspend fun deleteByServer(serverId: String) {
            delegate.deleteByServer(serverId)
        }

        override suspend fun deleteByPromptId(promptId: String) {
            delegate.deleteByPromptId(promptId)
        }

        override suspend fun deleteAll() {
            delegate.deleteAll()
        }
    }

    private class RecordingActionGateway : OutputGalleryActionGateway {
        val calls = mutableListOf<String>()

        override fun canSave(target: OutputGalleryActionTarget): Boolean = true

        override fun canShare(target: OutputGalleryActionTarget): Boolean = true

        override suspend fun save(target: OutputGalleryActionTarget): OutputGalleryActionResult {
            calls += "save:${target.ref.filename}"
            return OutputGalleryActionResult.Success
        }

        override suspend fun share(target: OutputGalleryActionTarget): OutputGalleryActionResult {
            calls += "share:${target.ref.filename}"
            return OutputGalleryActionResult.Success
        }
    }
}
