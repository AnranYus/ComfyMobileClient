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

    @Test fun showRunOutputs_uses_run_outputs_and_refreshes_favorite_from_repository() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(job("prompt-1", isFavorite = true))
        val vm = viewModel(repository = repo, scope = this)

        vm.showRunOutputs(
            promptId = "prompt-1",
            outputs = listOf(
                JobOutputRef("one.png", "", "output"),
                JobOutputRef("two.png", "batch", "output"),
            ),
            title = "Fresh run",
        )
        runCurrent()

        assertEquals("Fresh run", vm.state.value.title)
        assertEquals(listOf("one.png", "two.png"), vm.state.value.items.map { it.ref.filename })
        assertEquals(true, vm.state.value.isFavorite)
        assertEquals("prompt-1", vm.state.value.promptId)
    }

    @Test fun showPrompt_loads_stable_history_source_from_repository() = runTest {
        val repo = InMemoryJobRepository()
        repo.upsert(
            job(
                promptId = "prompt-1",
                label = "History row",
                firstOutput = JobOutputRef("history.png", "archive", "output"),
                isFavorite = true,
            ),
        )
        val vm = viewModel(repository = repo, scope = this)

        vm.showPrompt("prompt-1")
        runCurrent()

        assertEquals("History row", vm.state.value.title)
        assertEquals("history.png", vm.state.value.items.single().ref.filename)
        assertEquals("archive", vm.state.value.items.single().ref.subfolder)
        assertEquals(true, vm.state.value.isFavorite)
    }

    @Test fun stale_showPrompt_result_does_not_mutate_new_gallery_session() = runTest {
        val repo = DelayedLookupRepository()
        repo.upsert(
            job(
                promptId = "prompt-a",
                firstOutput = JobOutputRef("a.png", "", "output"),
                isFavorite = true,
            ),
        )
        val vm = viewModel(repository = repo, scope = this)

        vm.showPrompt("prompt-a")
        repo.lookupStarted.await()

        vm.showRunOutputs(
            promptId = "prompt-b",
            outputs = listOf(JobOutputRef("b.png", "", "output")),
            title = "Current",
        )
        repo.allowLookup.complete(Unit)
        runCurrent()

        assertEquals("prompt-b", vm.state.value.promptId)
        assertEquals("b.png", vm.state.value.items.single().ref.filename)
        assertEquals(false, vm.state.value.isFavorite)
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

    @Test fun delayed_share_clears_busy_when_viewer_selection_changes_before_result() = runTest {
        val gateway = DelayedShareGateway()
        val vm = viewModel(actionGateway = gateway, scope = this)

        vm.show(outputs = listOf(ComfyOutputRef("one.png", "", "output")))

        vm.actions().onShareSelected()
        gateway.shareStarted.await()
        assertEquals(OutputGalleryAction.Share, vm.state.value.actionInProgress)

        vm.actions().onCloseViewer()
        gateway.allowShare.complete(Unit)
        runCurrent()

        assertEquals(null, vm.state.value.selectedIndex)
        assertEquals(null, vm.state.value.actionInProgress)
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

    private fun job(
        promptId: String,
        label: String? = null,
        firstOutput: JobOutputRef? = null,
        isFavorite: Boolean = false,
    ): Job = Job(
        promptId = promptId,
        serverId = "srv-A",
        status = JobStatus.SUCCEEDED,
        label = label,
        firstOutput = firstOutput,
        isFavorite = isFavorite,
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

        override fun observeSucceededWithFirstOutputByServer(serverId: String): Flow<List<Job>> =
            delegate.observeSucceededWithFirstOutputByServer(serverId)

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

    private class DelayedLookupRepository : JobRepository {
        private val delegate = InMemoryJobRepository()
        val lookupStarted = CompletableDeferred<Unit>()
        val allowLookup = CompletableDeferred<Unit>()

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
            delegate.updateFavorite(promptId, isFavorite)
        }

        override suspend fun getByPromptId(promptId: String): Job? {
            lookupStarted.complete(Unit)
            allowLookup.await()
            return delegate.getByPromptId(promptId)
        }

        override suspend fun listByServer(serverId: String, limit: Int, offset: Int): List<Job> =
            delegate.listByServer(serverId, limit, offset)

        override fun observeByServer(serverId: String, limit: Int, offset: Int): Flow<List<Job>> =
            delegate.observeByServer(serverId, limit, offset)

        override fun observeSucceededWithFirstOutputByServer(serverId: String): Flow<List<Job>> =
            delegate.observeSucceededWithFirstOutputByServer(serverId)

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

    private class DelayedShareGateway : OutputGalleryActionGateway {
        val shareStarted = CompletableDeferred<Unit>()
        val allowShare = CompletableDeferred<Unit>()

        override fun canShare(target: OutputGalleryActionTarget): Boolean = true

        override suspend fun share(target: OutputGalleryActionTarget): OutputGalleryActionResult {
            shareStarted.complete(Unit)
            allowShare.await()
            return OutputGalleryActionResult.Success
        }
    }
}
