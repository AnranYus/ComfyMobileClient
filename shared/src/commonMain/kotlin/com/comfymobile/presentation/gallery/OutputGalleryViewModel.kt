package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.image.PreviewFormat
import com.comfymobile.data.image.PreviewSpec
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OutputGalleryViewModel(
    private val imageMapper: ComfyImageMapper,
    private val jobRepository: JobRepository? = null,
    private val actionGateway: OutputGalleryActionGateway = DisabledOutputGalleryActionGateway,
    private val scope: CoroutineScope? = null,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private var galleryGeneration: Long = 0L
    private var latestSourceRequestId: Long = 0L
    private var latestActionId: Long = 0L

    private val mutableState = MutableStateFlow(
        OutputGalleryState(
            title = OutputGalleryCopy.title.resolve(language),
            language = language,
        ),
    )
    val state: StateFlow<OutputGalleryState> = mutableState.asStateFlow()

    fun actions(): OutputGalleryActions = OutputGalleryActions(
        onBack = ::close,
        onOpenItem = ::openItem,
        onCloseViewer = ::closeViewer,
        onToggleMetadata = ::toggleMetadata,
        onLongPressItem = ::toggleBatchSelection,
        onToggleBatchSelection = ::toggleBatchSelection,
        onSaveSelected = ::saveSelected,
        onShareSelected = ::shareSelected,
        onToggleFavorite = ::toggleFavorite,
        onRunAgain = {},
        onTweakAndRun = {},
        onViewWorkflow = {},
    )

    fun show(
        outputs: List<ComfyOutputRef>,
        title: String = OutputGalleryCopy.title.resolve(language),
        metadata: OutputMetadata = OutputMetadata(),
        promptId: String? = null,
        isFavorite: Boolean = false,
    ) {
        latestSourceRequestId += 1L
        galleryGeneration += 1L
        val items = OutputGalleryMapper.items(
            outputs = outputs,
            imageMapper = imageMapper,
            preview = PreviewSpec(
                format = PreviewFormat.JPEG,
                quality = 90,
            ),
        )
        mutableState.value = OutputGalleryState(
            title = title,
            items = items,
            promptId = promptId,
            selectedIndex = if (items.size == 1) 0 else null,
            metadata = metadata,
            isFavorite = isFavorite,
            language = language,
        ).withActionAvailability()
    }

    fun showRunOutputs(
        promptId: String,
        outputs: List<JobOutputRef>,
        title: String = OutputGalleryCopy.title.resolve(language),
        metadata: OutputMetadata = OutputMetadata(),
    ) {
        val mappedOutputs = outputs.map { it.toComfyOutputRef() }
        show(
            outputs = mappedOutputs,
            title = title,
            metadata = metadata,
            promptId = promptId,
            isFavorite = false,
        )
        refreshFavorite(promptId = promptId, generation = galleryGeneration)
    }

    fun showPrompt(promptId: String) {
        val repository = jobRepository ?: return
        val targetScope = scope ?: return
        val requestId = ++latestSourceRequestId
        targetScope.launch {
            val job = try {
                repository.getByPromptId(promptId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } ?: return@launch
            if (requestId != latestSourceRequestId) return@launch
            show(
                outputs = job.outputsForGallery(),
                title = job.galleryTitle(),
                promptId = job.promptId,
                isFavorite = job.isFavorite,
            )
        }
    }

    fun close() {
        mutableState.value = mutableState.value.copy(
            selectedIndex = null,
            selectedForBatch = emptySet(),
        )
    }

    fun openItem(index: Int) {
        if (index !in mutableState.value.items.indices) return
        mutableState.value = mutableState.value.copy(selectedIndex = index).withActionAvailability()
    }

    fun closeViewer() {
        mutableState.value = mutableState.value.copy(selectedIndex = null).withActionAvailability()
    }

    fun toggleMetadata() {
        mutableState.value = mutableState.value.copy(
            metadataExpanded = !mutableState.value.metadataExpanded,
        )
    }

    fun toggleBatchSelection(index: Int) {
        if (index !in mutableState.value.items.indices) return
        val selected = mutableState.value.selectedForBatch
        mutableState.value = mutableState.value.copy(
            selectedForBatch = if (index in selected) selected - index else selected + index,
        )
    }

    private fun saveSelected() {
        val selected = mutableState.value.selectedItem ?: return
        if (!mutableState.value.saveEnabled) return
        val session = mutableState.value.currentSession()
        launchAction(OutputGalleryAction.Save, session) { _ ->
            actionGateway.save(selected.toActionTarget())
        }
    }

    private fun shareSelected() {
        val selected = mutableState.value.selectedItem ?: return
        if (!mutableState.value.shareEnabled) return
        val session = mutableState.value.currentSession()
        launchAction(OutputGalleryAction.Share, session) { _ ->
            actionGateway.share(selected.toActionTarget())
        }
    }

    private fun toggleFavorite() {
        val current = mutableState.value
        val promptId = current.promptId ?: return
        val repository = jobRepository ?: return
        if (!current.favoriteEnabled) return
        val nextFavorite = !current.isFavorite
        val session = current.currentSession()
        launchAction(OutputGalleryAction.Favorite, session) { isCurrent ->
            repository.updateFavorite(promptId, nextFavorite)
            if (isCurrent()) {
                mutableState.value = mutableState.value.copy(isFavorite = nextFavorite)
            }
        }
    }

    private fun refreshFavorite(promptId: String, generation: Long) {
        val repository = jobRepository ?: return
        val targetScope = scope ?: return
        targetScope.launch {
            val job = try {
                repository.getByPromptId(promptId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } ?: return@launch
            if (galleryGeneration != generation || mutableState.value.promptId != promptId) return@launch
            mutableState.value = mutableState.value.copy(isFavorite = job.isFavorite).withActionAvailability()
        }
    }

    private fun launchAction(
        action: OutputGalleryAction,
        session: GallerySession,
        block: suspend (isCurrent: () -> Boolean) -> Unit,
    ) {
        val targetScope = scope ?: return
        if (mutableState.value.actionInProgress != null) return
        val actionId = ++latestActionId
        mutableState.value = mutableState.value.copy(actionInProgress = action).withActionAvailability()
        targetScope.launch {
            try {
                block { isCurrent(session, actionId) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Later platform implementations can surface a localized
                // toast/snackbar. For now, keep the preparatory seam silent
                // and side-effect free on failure.
            } finally {
                if (isCurrent(session, actionId)) {
                    mutableState.value = mutableState.value.copy(actionInProgress = null).withActionAvailability()
                }
            }
        }
    }

    private fun OutputGalleryState.currentSession(): GallerySession =
        GallerySession(
            generation = galleryGeneration,
            promptId = promptId,
            selectedOutputKey = selectedItem?.ref?.identityKey,
        )

    private fun isCurrent(session: GallerySession, actionId: Long): Boolean =
        actionId == latestActionId &&
            galleryGeneration == session.generation &&
            mutableState.value.promptId == session.promptId &&
            mutableState.value.selectedItem?.ref?.identityKey == session.selectedOutputKey

    private fun OutputGalleryState.withActionAvailability(): OutputGalleryState {
        val target = selectedItem?.toActionTarget()
        val busy = actionInProgress != null
        return copy(
            saveEnabled = !busy && target?.let { actionGateway.canSave(it) } == true,
            shareEnabled = !busy && target?.let { actionGateway.canShare(it) } == true,
            favoriteEnabled = !busy && promptId != null && jobRepository != null && scope != null,
        )
    }

    private data class GallerySession(
        val generation: Long,
        val promptId: String?,
        val selectedOutputKey: String?,
    )

    private val ComfyOutputRef.identityKey: String
        get() = "$type/$subfolder/$filename"

    private fun Job.outputsForGallery(): List<ComfyOutputRef> =
        listOfNotNull(firstOutput?.toComfyOutputRef())

    private fun Job.galleryTitle(): String =
        label?.takeIf { it.isNotBlank() } ?: OutputGalleryCopy.title.resolve(language)

    private fun JobOutputRef.toComfyOutputRef(): ComfyOutputRef =
        ComfyOutputRef(
            filename = filename,
            subfolder = subfolder,
            type = type,
        )
}
