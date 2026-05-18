package com.comfymobile.presentation.library

import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.ConnectionStatusTone
import com.comfymobile.presentation.connection.LocalizedText

data class WorkflowLibraryScreenState(
    val rows: List<WorkflowLibraryRowState> = emptyList(),
    val activeServerLabel: String? = null,
    val connectionLabel: LocalizedText? = null,
    val connectionTone: ConnectionStatusTone = ConnectionStatusTone.Subtle,
    val connectionPulsing: Boolean = false,
    val pendingDelete: WorkflowLibraryRowState? = null,
    val pendingRename: WorkflowLibraryRowState? = null,
    val renameDraft: String = "",
    val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    val isEmpty: Boolean
        get() = rows.isEmpty()

    val canConfirmRename: Boolean
        get() {
            val target = pendingRename ?: return false
            val trimmed = renameDraft.trim()
            return trimmed.isNotEmpty() && trimmed != target.title
        }
}

data class WorkflowLibraryRowState(
    val workflowId: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val nodeCount: Int,
    val format: WorkflowLibraryFormat,
    val importedAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long?,
)

enum class WorkflowLibraryFormat {
    Ui,
    Api,
}

data class WorkflowLibraryActions(
    val onImport: () -> Unit = {},
    val onOpenWorkflow: (String) -> Unit = {},
    val onRenameRequested: (String) -> Unit = {},
    val onRenameValueChanged: (String) -> Unit = {},
    val onDismissRename: () -> Unit = {},
    val onConfirmRename: () -> Unit = {},
    val onDeleteRequested: (String) -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onConfirmDelete: (String) -> Unit = {},
)

object WorkflowLibraryCopy {
    val title = LocalizedText(zh = "工作流", en = "Workflows")
    val importWorkflow = LocalizedText(zh = "导入工作流", en = "Import workflow")
    val rename = LocalizedText(zh = "重命名", en = "Rename")
    val renameTitle = LocalizedText(zh = "重命名工作流", en = "Rename workflow")
    val workflowName = LocalizedText(zh = "工作流名称", en = "Workflow name")
    val save = LocalizedText(zh = "保存", en = "Save")
    val emptyTitle = LocalizedText(zh = "还没有工作流", en = "No workflows yet")
    val emptyBody = LocalizedText(
        zh = "导入 JSON 或带工作流的 PNG 后会出现在这里。",
        en = "Imported JSON files or PNG workflows will appear here.",
    )
    val delete = LocalizedText(zh = "删除", en = "Delete")
    val deleteTitle = LocalizedText(zh = "删除工作流？", en = "Delete workflow?")
    val deleteBody = LocalizedText(
        zh = "这只会从移动端资料库移除工作流，不会删除 ComfyUI 服务器上的历史或图片。",
        en = "This only removes the workflow from the mobile library. It does not delete server history or images.",
    )
    val cancel = LocalizedText(zh = "取消", en = "Cancel")
    val imported = LocalizedText(zh = "已导入", en = "Imported")
    val lastOpened = LocalizedText(zh = "最近打开", en = "Last opened")
    val connectionUnavailable = LocalizedText(zh = "未连接服务器", en = "No active server")

    fun nodeCount(count: Int, language: ConnectionLanguage): String = when (language) {
        ConnectionLanguage.Zh -> "$count 个节点"
        ConnectionLanguage.En -> "$count nodes"
    }

    fun format(format: WorkflowLibraryFormat, language: ConnectionLanguage): String = when (format) {
        WorkflowLibraryFormat.Ui -> when (language) {
            ConnectionLanguage.Zh -> "UI 工作流"
            ConnectionLanguage.En -> "UI workflow"
        }
        WorkflowLibraryFormat.Api -> when (language) {
            ConnectionLanguage.Zh -> "API 工作流"
            ConnectionLanguage.En -> "API workflow"
        }
    }
}
