package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.image.PreviewFormat
import com.comfymobile.data.image.PreviewSpec
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OutputGalleryViewModel(
    private val imageMapper: ComfyImageMapper,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
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
        onRunAgain = {},
        onTweakAndRun = {},
        onViewWorkflow = {},
    )

    fun show(
        outputs: List<ComfyOutputRef>,
        title: String = OutputGalleryCopy.title.resolve(language),
        metadata: OutputMetadata = OutputMetadata(),
    ) {
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
            selectedIndex = if (items.size == 1) 0 else null,
            metadata = metadata,
            language = language,
        )
    }

    fun close() {
        mutableState.value = mutableState.value.copy(
            selectedIndex = null,
            selectedForBatch = emptySet(),
        )
    }

    fun openItem(index: Int) {
        if (index !in mutableState.value.items.indices) return
        mutableState.value = mutableState.value.copy(selectedIndex = index)
    }

    fun closeViewer() {
        mutableState.value = mutableState.value.copy(selectedIndex = null)
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
}
