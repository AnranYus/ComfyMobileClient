package com.comfymobile.presentation.library

import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun WorkflowRow.toLibraryRowState(
    thumbnailUrl: String? = null,
): WorkflowLibraryRowState = WorkflowLibraryRowState(
    workflowId = workflowId,
    title = displayName,
    thumbnailUrl = thumbnailUrl,
    nodeCount = envelope.nodeCount(),
    format = when (envelope.format) {
        WorkflowFormat.UI -> WorkflowLibraryFormat.Ui
        WorkflowFormat.API -> WorkflowLibraryFormat.Api
    },
    importedAtEpochMs = importedAtEpochMs,
    lastOpenedAtEpochMs = lastOpenedAtEpochMs,
)

private fun com.comfymobile.domain.workflow.WorkflowEnvelope.nodeCount(): Int = when (format) {
    WorkflowFormat.UI -> {
        val root = original as? JsonObject
        (root?.get("nodes") as? JsonArray)?.size ?: 0
    }
    WorkflowFormat.API -> {
        val root = original as? JsonObject
        root?.size ?: 0
    }
}
