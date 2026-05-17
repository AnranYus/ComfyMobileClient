package com.comfymobile.presentation.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfymobile.data.importer.PlatformWorkflowImportGateway
import com.comfymobile.data.importer.rememberPlatformWorkflowImportGateway
import com.comfymobile.data.workflow.WorkflowImportWarning
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.ConnectionLanguage.En
import com.comfymobile.presentation.connection.ConnectionLanguage.Zh
import com.comfymobile.presentation.connection.LocalizedText
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun WorkflowImportRoute(
    viewModel: WorkflowImportViewModel,
    modifier: Modifier = Modifier,
    showFab: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    val actions = viewModel.actions()
    val gateway = rememberPlatformWorkflowImportGateway(
        onPayload = actions.onPayload,
        onFailure = actions.onPlatformFailure,
    )

    WorkflowImportOverlay(
        state = state,
        actions = actions,
        gateway = gateway,
        modifier = modifier,
        showFab = showFab,
    )
}

@Composable
fun WorkflowImportOverlay(
    state: WorkflowImportScreenState,
    actions: WorkflowImportActions,
    gateway: PlatformWorkflowImportGateway,
    modifier: Modifier = Modifier,
    showFab: Boolean = true,
) {
    Box(modifier = modifier) {
        if (showFab) {
            ExtendedFloatingActionButton(
                onClick = actions.onOpenSheet,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                Text(ImportCopy.importWorkflow.resolve(state.language))
            }
        }

        if (state.isParsing) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(ImportCopy.reading.resolve(state.language))
                }
            }
        }
    }

    if (state.actionSheetVisible) {
        ImportActionDialog(
            language = state.language,
            gateway = gateway,
            onDismiss = actions.onDismissSheet,
        )
    }

    if (state.preview != null) {
        ImportPreviewDialog(
            state = state,
            onDisplayNameChanged = actions.onDisplayNameChanged,
            onConfirm = actions.onConfirmImport,
            onCancel = actions.onCancelPreview,
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = actions.onDismissError,
            title = { Text(error.title(state.language)) },
            text = { Text(error.body(state.language)) },
            confirmButton = {
                TextButton(onClick = actions.onDismissError) {
                    Text(ImportCopy.ok.resolve(state.language))
                }
            },
        )
    }

    state.lastImportedRow?.let { row ->
        AlertDialog(
            onDismissRequest = actions.onDismissSuccess,
            title = { Text(ImportCopy.imported.resolve(state.language)) },
            text = { Text(row.displayName) },
            confirmButton = {
                TextButton(onClick = actions.onDismissSuccess) {
                    Text(ImportCopy.ok.resolve(state.language))
                }
            },
        )
    }
}

@Composable
private fun ImportActionDialog(
    language: ConnectionLanguage,
    gateway: PlatformWorkflowImportGateway,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ImportCopy.importWorkflow.resolve(language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportPathButton(ImportCopy.pickFile.resolve(language)) {
                    onDismiss()
                    gateway.pickFile()
                }
                ImportPathButton(ImportCopy.importShared.resolve(language)) {
                    onDismiss()
                    gateway.consumeSharedPayload()
                }
                ImportPathButton(ImportCopy.pasteJson.resolve(language)) {
                    onDismiss()
                    gateway.pasteText()
                }
                ImportPathButton(ImportCopy.pastePng.resolve(language)) {
                    onDismiss()
                    gateway.pasteImage()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ImportCopy.cancel.resolve(language))
            }
        },
    )
}

@Composable
private fun ImportPathButton(
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun ImportPreviewDialog(
    state: WorkflowImportScreenState,
    onDisplayNameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.preview ?: return
    val language = state.language
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(ImportCopy.previewTitle.resolve(language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.displayNameInput,
                    onValueChange = onDisplayNameChanged,
                    label = { Text(ImportCopy.workflowName.resolve(language)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = nodeStatsText(
                        total = draft.nodeStats.totalNodes,
                        supported = draft.nodeStats.supportedEditableNodes,
                        unknown = draft.nodeStats.unknownNodes,
                        language = language,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                draft.warnings.forEach { warning ->
                    Text(
                        text = warningText(warning, language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = state.displayNameInput.isNotBlank(),
            ) {
                Text(ImportCopy.confirmImport.resolve(language))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(ImportCopy.cancel.resolve(language))
            }
        },
    )
}

private fun nodeStatsText(
    total: Int,
    supported: Int,
    unknown: Int,
    language: ConnectionLanguage,
): String = when (language) {
    Zh -> "$total 个节点（$supported 个支持移动端编辑，$unknown 个未知）"
    En -> "$total nodes ($supported editable on mobile, $unknown unknown)"
}

private fun warningText(
    warning: WorkflowImportWarning,
    language: ConnectionLanguage,
): String = when (warning) {
    is WorkflowImportWarning.LargeFile -> when (language) {
        Zh -> "文件较大，解析可能需要一点时间。"
        En -> "Large file. Parsing may take a moment."
    }
    is WorkflowImportWarning.MostlyReadOnlyUnknownNodes -> when (language) {
        Zh -> "多数节点暂时只能只读查看。"
        En -> "Most nodes will be read-only for now."
    }
}

private object ImportCopy {
    val importWorkflow = LocalizedText(zh = "导入工作流", en = "Import workflow")
    val reading = LocalizedText(zh = "正在读取…", en = "Reading…")
    val pickFile = LocalizedText(zh = "选择 JSON 或 PNG 文件", en = "Choose JSON or PNG file")
    val importShared = LocalizedText(zh = "导入分享来的文件", en = "Import shared file")
    val pasteJson = LocalizedText(zh = "粘贴 JSON", en = "Paste JSON")
    val pastePng = LocalizedText(zh = "粘贴 PNG", en = "Paste PNG")
    val previewTitle = LocalizedText(zh = "导入预览", en = "Import preview")
    val workflowName = LocalizedText(zh = "工作流名称", en = "Workflow name")
    val confirmImport = LocalizedText(zh = "导入", en = "Import")
    val imported = LocalizedText(zh = "已导入", en = "Imported")
    val cancel = LocalizedText(zh = "取消", en = "Cancel")
    val ok = LocalizedText(zh = "好的", en = "OK")
}

@Preview
@Composable
private fun WorkflowImportActionPreview() {
    MaterialTheme {
        WorkflowImportOverlay(
            state = WorkflowImportScreenState(actionSheetVisible = true),
            actions = WorkflowImportActions(),
            gateway = object : PlatformWorkflowImportGateway {
                override fun pickFile() = Unit
                override fun consumeSharedPayload() = Unit
                override fun pasteText() = Unit
                override fun pasteImage() = Unit
            },
        )
    }
}
