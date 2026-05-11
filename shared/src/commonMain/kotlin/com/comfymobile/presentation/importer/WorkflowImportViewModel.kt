package com.comfymobile.presentation.importer

import com.comfymobile.data.importer.PlatformWorkflowImportFailure
import com.comfymobile.data.importer.PlatformWorkflowImportFailureReason
import com.comfymobile.data.importer.PlatformWorkflowImportPayload
import com.comfymobile.data.workflow.WorkflowImportDraft
import com.comfymobile.data.workflow.WorkflowImportError
import com.comfymobile.data.workflow.WorkflowImportPreparationOutcome
import com.comfymobile.data.workflow.WorkflowImporter
import com.comfymobile.domain.workflow.WorkflowRow
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkflowImportViewModel(
    private val importerFactory: () -> WorkflowImporter,
    private val scope: CoroutineScope,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val mutableState = MutableStateFlow(WorkflowImportScreenState(language = language))
    val state: StateFlow<WorkflowImportScreenState> = mutableState.asStateFlow()

    fun actions(): WorkflowImportActions = WorkflowImportActions(
        onOpenSheet = ::openSheet,
        onDismissSheet = ::dismissSheet,
        onPayload = ::onPayload,
        onPlatformFailure = ::onPlatformFailure,
        onDisplayNameChanged = ::onDisplayNameChanged,
        onConfirmImport = ::confirmImport,
        onCancelPreview = ::cancelPreview,
        onDismissError = ::dismissError,
        onDismissSuccess = ::dismissSuccess,
    )

    fun openSheet() {
        mutableState.value = mutableState.value.copy(
            actionSheetVisible = true,
            error = null,
            lastImportedRow = null,
        )
    }

    fun dismissSheet() {
        mutableState.value = mutableState.value.copy(actionSheetVisible = false)
    }

    fun onPayload(payload: PlatformWorkflowImportPayload) {
        mutableState.value = mutableState.value.copy(
            actionSheetVisible = false,
            isParsing = true,
            error = null,
            lastImportedRow = null,
        )
        scope.launch {
            val prepared = when (payload) {
                is PlatformWorkflowImportPayload.Text -> importerFactory().prepareJsonText(
                    text = payload.value,
                    source = payload.source,
                    sourceName = payload.sourceName,
                )
                is PlatformWorkflowImportPayload.Bytes -> importerFactory().preparePngBytes(
                    bytes = payload.value,
                    source = payload.source,
                    sourceName = payload.sourceName,
                )
            }
            mutableState.value = when (prepared) {
                is WorkflowImportPreparationOutcome.Ready -> mutableState.value.copy(
                    isParsing = false,
                    preview = prepared.draft,
                    displayNameInput = prepared.draft.defaultDisplayName,
                    error = null,
                )
                is WorkflowImportPreparationOutcome.Failure -> mutableState.value.copy(
                    isParsing = false,
                    preview = null,
                    error = WorkflowImportUiError.Import(prepared.error),
                )
            }
        }
    }

    fun onPlatformFailure(failure: PlatformWorkflowImportFailure) {
        mutableState.value = mutableState.value.copy(
            actionSheetVisible = false,
            isParsing = false,
            error = WorkflowImportUiError.Platform(failure),
        )
    }

    fun onDisplayNameChanged(value: String) {
        mutableState.value = mutableState.value.copy(displayNameInput = value)
    }

    fun confirmImport() {
        val draft = mutableState.value.preview ?: return
        mutableState.value = mutableState.value.copy(isParsing = true, error = null)
        scope.launch {
            val imported = importerFactory().commit(
                draft = draft,
                displayName = mutableState.value.displayNameInput,
            )
            mutableState.value = mutableState.value.copy(
                isParsing = false,
                preview = null,
                displayNameInput = "",
                lastImportedRow = imported.row,
            )
        }
    }

    fun cancelPreview() {
        mutableState.value = mutableState.value.copy(
            preview = null,
            displayNameInput = "",
        )
    }

    fun dismissError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun dismissSuccess() {
        mutableState.value = mutableState.value.copy(lastImportedRow = null)
    }
}

data class WorkflowImportScreenState(
    val actionSheetVisible: Boolean = false,
    val isParsing: Boolean = false,
    val preview: WorkflowImportDraft? = null,
    val displayNameInput: String = "",
    val error: WorkflowImportUiError? = null,
    val lastImportedRow: WorkflowRow? = null,
    val language: ConnectionLanguage = ConnectionLanguage.En,
)

data class WorkflowImportActions(
    val onOpenSheet: () -> Unit = {},
    val onDismissSheet: () -> Unit = {},
    val onPayload: (PlatformWorkflowImportPayload) -> Unit = {},
    val onPlatformFailure: (PlatformWorkflowImportFailure) -> Unit = {},
    val onDisplayNameChanged: (String) -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onCancelPreview: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onDismissSuccess: () -> Unit = {},
)

sealed interface WorkflowImportUiError {
    data class Import(val error: WorkflowImportError) : WorkflowImportUiError
    data class Platform(val failure: PlatformWorkflowImportFailure) : WorkflowImportUiError
}

fun WorkflowImportUiError.title(language: ConnectionLanguage): String = when (this) {
    is WorkflowImportUiError.Import -> when (error) {
        is WorkflowImportError.InvalidJson -> when (language) {
            ConnectionLanguage.Zh -> "这不是有效的工作流 JSON"
            ConnectionLanguage.En -> "This is not valid workflow JSON"
        }
        is WorkflowImportError.InvalidPng -> when (language) {
            ConnectionLanguage.Zh -> "无法读取这张 PNG"
            ConnectionLanguage.En -> "Could not read this PNG"
        }
        WorkflowImportError.NoWorkflowInPng -> when (language) {
            ConnectionLanguage.Zh -> "PNG 里没有发现工作流"
            ConnectionLanguage.En -> "No workflow found in this PNG"
        }
        WorkflowImportError.UnsupportedJsonShape -> when (language) {
            ConnectionLanguage.Zh -> "暂不支持这个工作流版本"
            ConnectionLanguage.En -> "This workflow version is not supported yet"
        }
        WorkflowImportError.EmptyWorkflow -> when (language) {
            ConnectionLanguage.Zh -> "工作流是空的"
            ConnectionLanguage.En -> "The workflow is empty"
        }
    }
    is WorkflowImportUiError.Platform -> when (failure.reason) {
        PlatformWorkflowImportFailureReason.NoFileSelected -> when (language) {
            ConnectionLanguage.Zh -> "没有选择文件"
            ConnectionLanguage.En -> "No file selected"
        }
        PlatformWorkflowImportFailureReason.NoSharedPayload -> when (language) {
            ConnectionLanguage.Zh -> "没有可导入的分享内容"
            ConnectionLanguage.En -> "No shared import payload"
        }
        PlatformWorkflowImportFailureReason.ClipboardEmpty -> when (language) {
            ConnectionLanguage.Zh -> "剪贴板是空的"
            ConnectionLanguage.En -> "Clipboard is empty"
        }
        PlatformWorkflowImportFailureReason.UnsupportedClipboardPayload -> when (language) {
            ConnectionLanguage.Zh -> "剪贴板内容不是 JSON 或 PNG"
            ConnectionLanguage.En -> "Clipboard content is not JSON or PNG"
        }
        PlatformWorkflowImportFailureReason.UnableToReadPayload -> when (language) {
            ConnectionLanguage.Zh -> "无法读取导入内容"
            ConnectionLanguage.En -> "Could not read import payload"
        }
    }
}

fun WorkflowImportUiError.body(language: ConnectionLanguage): String = when (this) {
    is WorkflowImportUiError.Import -> when (error) {
        is WorkflowImportError.InvalidJson -> when (language) {
            ConnectionLanguage.Zh -> "请选择 ComfyUI 导出的 JSON，或从带工作流信息的 PNG 导入。"
            ConnectionLanguage.En -> "Choose JSON exported by ComfyUI, or import from a PNG with workflow metadata."
        }
        is WorkflowImportError.InvalidPng -> error.reason.ifBlank {
            when (language) {
                ConnectionLanguage.Zh -> "图片文件可能已损坏。"
                ConnectionLanguage.En -> "The image file may be corrupt."
            }
        }
        WorkflowImportError.NoWorkflowInPng -> when (language) {
            ConnectionLanguage.Zh -> "这张图片没有 workflow 或 prompt 元数据。"
            ConnectionLanguage.En -> "This image does not contain workflow or prompt metadata."
        }
        WorkflowImportError.UnsupportedJsonShape -> when (language) {
            ConnectionLanguage.Zh -> "需要 UI workflow JSON 或 API prompt JSON。"
            ConnectionLanguage.En -> "Expected UI workflow JSON or API prompt JSON."
        }
        WorkflowImportError.EmptyWorkflow -> when (language) {
            ConnectionLanguage.Zh -> "这个文件没有任何节点。"
            ConnectionLanguage.En -> "This file has no nodes."
        }
    }
    is WorkflowImportUiError.Platform -> failure.detail ?: when (language) {
        ConnectionLanguage.Zh -> "请换一个入口重试。"
        ConnectionLanguage.En -> "Try a different import path."
    }
}
