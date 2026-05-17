package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText

data class OutputGalleryState(
    val title: String,
    val items: List<OutputGalleryItem> = emptyList(),
    val promptId: String? = null,
    val selectedIndex: Int? = null,
    val metadata: OutputMetadata = OutputMetadata(),
    val metadataExpanded: Boolean = false,
    val selectedForBatch: Set<Int> = emptySet(),
    val isFavorite: Boolean = false,
    val saveEnabled: Boolean = false,
    val shareEnabled: Boolean = false,
    val favoriteEnabled: Boolean = false,
    val actionInProgress: OutputGalleryAction? = null,
    val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val selectedItem: OutputGalleryItem?
        get() = selectedIndex?.let { index -> items.getOrNull(index) }
    val isViewerOpen: Boolean get() = selectedItem != null
    val isSelectionMode: Boolean get() = selectedForBatch.isNotEmpty()
}

enum class OutputGalleryAction {
    Save,
    Share,
    Favorite,
}

data class OutputGalleryItem(
    val ref: ComfyOutputRef,
    val imageUrl: String?,
    val contentDescription: String,
)

data class OutputMetadata(
    val modelName: String? = null,
    val prompt: String? = null,
    val seed: String? = null,
    val dimensions: String? = null,
    val elapsed: String? = null,
) {
    val hasAnyValue: Boolean
        get() = listOf(modelName, prompt, seed, dimensions, elapsed).any { !it.isNullOrBlank() }
}

data class OutputGalleryActions(
    val onBack: () -> Unit,
    val onOpenItem: (Int) -> Unit,
    val onCloseViewer: () -> Unit,
    val onToggleMetadata: () -> Unit,
    val onLongPressItem: (Int) -> Unit,
    val onToggleBatchSelection: (Int) -> Unit,
    val onSaveSelected: () -> Unit,
    val onShareSelected: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onRunAgain: () -> Unit,
    val onTweakAndRun: () -> Unit,
    val onViewWorkflow: () -> Unit,
)

object OutputGalleryCopy {
    val title = LocalizedText(zh = "产物", en = "Outputs")
    val empty = LocalizedText(zh = "首次生成会出现在这里", en = "Your generations will appear here.")
    val save = LocalizedText(zh = "保存到相册", en = "Save to gallery")
    val share = LocalizedText(zh = "分享", en = "Share")
    val favorite = LocalizedText(zh = "收藏", en = "Favorite")
    val favorited = LocalizedText(zh = "已收藏", en = "Favorited")
    val runAgain = LocalizedText(zh = "用此工作流再生", en = "Run again")
    val tweakAndRun = LocalizedText(zh = "微调再生", en = "Tweak & run")
    val metadata = LocalizedText(zh = "参数", en = "Metadata")
    val comingSoon = LocalizedText(zh = "下一个版本支持", en = "Coming in next version")
    val model = LocalizedText(zh = "模型", en = "Model")
    val prompt = LocalizedText(zh = "提示词", en = "Prompt")
    val seed = LocalizedText(zh = "随机种子", en = "Seed")
    val dimensions = LocalizedText(zh = "尺寸", en = "Dimensions")
    val elapsed = LocalizedText(zh = "耗时", en = "Time elapsed")
    val viewWorkflow = LocalizedText(zh = "查看完整工作流", en = "View full workflow")
    val noImageUrl = LocalizedText(zh = "未连接服务器", en = "No active server")
}
