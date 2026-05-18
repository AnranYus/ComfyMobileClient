package com.comfymobile.presentation.library

import androidx.compose.runtime.Composable

data class WorkflowExportRequest(
    val workflowId: String,
    val actionId: Long,
    val fileName: String,
    val json: String,
)

sealed interface WorkflowExportResult {
    data object Success : WorkflowExportResult
    data object Cancelled : WorkflowExportResult
    data object Unsupported : WorkflowExportResult
    data class Failed(val message: String? = null) : WorkflowExportResult
}

interface PlatformWorkflowExportGateway {
    fun exportJson(request: WorkflowExportRequest)
}

@Composable
expect fun rememberPlatformWorkflowExportGateway(
    onResult: (WorkflowExportRequest, WorkflowExportResult) -> Unit,
): PlatformWorkflowExportGateway
