package com.comfymobile.presentation.history

import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText

enum class HistoryFilter {
    All,
    Successful,
    Running,
    FailedCancelled,
}

data class HistoryScreenState(
    val rows: List<HistoryRowState> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.All,
    val activeServerLabel: String? = null,
    val isReconciling: Boolean = false,
    val renamePromptId: String? = null,
    val renameDraft: String = "",
    val deletePromptId: String? = null,
    val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    val hasActiveServer: Boolean get() = activeServerLabel != null
    val isEmpty: Boolean get() = rows.isEmpty()
    val deleteRow: HistoryRowState? get() = rows.firstOrNull { it.promptId == deletePromptId }
}

data class HistoryRowState(
    val promptId: String,
    val title: String,
    val promptSnippet: String,
    val relativeTime: String,
    val status: HistoryStatusPresentation,
    val thumbnailUrl: String? = null,
    val canOpenWorkflow: Boolean = false,
)

data class HistoryStatusPresentation(
    val symbol: String,
    val label: LocalizedText,
    val tone: HistoryStatusTone,
)

enum class HistoryStatusTone {
    Neutral,
    Success,
    Running,
    Error,
}

data class HistoryActions(
    val onFilterSelected: (HistoryFilter) -> Unit,
    val onOpenOutputs: (String) -> Unit,
    val onOpenWorkflow: (String) -> Unit,
    val onRenameRequested: (String) -> Unit,
    val onRenameValueChanged: (String) -> Unit,
    val onConfirmRename: () -> Unit,
    val onDismissRename: () -> Unit,
    val onDeleteRequested: (String) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
)

object HistoryCopy {
    val title = LocalizedText(zh = "历史", en = "History")
    val empty = LocalizedText(
        zh = "还没有生成记录。试试在工作流里点\"生图\"。",
        en = "No generations yet. Try tapping \"Generate\" in a workflow.",
    )
    val noActiveServer = LocalizedText(
        zh = "连接一台服务器后查看它的生成记录。",
        en = "Connect to a server to see its generation history.",
    )
    val all = LocalizedText(zh = "全部", en = "All")
    val successful = LocalizedText(zh = "成功", en = "Successful")
    val running = LocalizedText(zh = "进行中", en = "Running")
    val failedCancelled = LocalizedText(zh = "失败/取消", en = "Failed/Cancelled")
    val rename = LocalizedText(zh = "重命名", en = "Rename")
    val openWorkflow = LocalizedText(zh = "打开工作流", en = "Open workflow")
    val delete = LocalizedText(zh = "删除", en = "Delete")
    val cancel = LocalizedText(zh = "取消", en = "Cancel")
    val save = LocalizedText(zh = "保存", en = "Save")
    val deleteTitle = LocalizedText(zh = "删除这条记录？", en = "Delete this record?")
    val deleteBody = LocalizedText(
        zh = "这会从本机历史里移除记录，不会影响服务器上的图片文件。",
        en = "This removes the local history row and does not delete files on the server.",
    )
    val thumbnail = LocalizedText(zh = "缩略图", en = "Thumbnail")
    val placeholder = LocalizedText(zh = "暂无产物", en = "No output")
    val promptMissing = LocalizedText(zh = "未记录提示词", en = "No prompt recorded")
    val justNow = LocalizedText(zh = "刚刚", en = "just now")
    val queued = LocalizedText(zh = "排队中", en = "Queued")
    val processing = LocalizedText(zh = "生成中", en = "Running")
    val succeeded = LocalizedText(zh = "成功", en = "Succeeded")
    val failed = LocalizedText(zh = "失败", en = "Failed")
    val interrupted = LocalizedText(zh = "已取消", en = "Cancelled")

    fun filterLabel(filter: HistoryFilter): LocalizedText = when (filter) {
        HistoryFilter.All -> all
        HistoryFilter.Successful -> successful
        HistoryFilter.Running -> running
        HistoryFilter.FailedCancelled -> failedCancelled
    }
}
