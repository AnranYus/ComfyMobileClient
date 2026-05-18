package com.comfymobile.presentation.library

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPlatformWorkflowExportGateway(
    onResult: (WorkflowExportRequest, WorkflowExportResult) -> Unit,
): PlatformWorkflowExportGateway {
    val context = LocalContext.current
    val latestOnResult by rememberUpdatedState(onResult)
    val pending = remember { mutableStateOf<WorkflowExportRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val request = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        val result = if (uri == null) {
            WorkflowExportResult.Cancelled
        } else {
            context.writeWorkflowExport(uri, request)
        }
        latestOnResult(request, result)
    }
    return remember(context, launcher) {
        AndroidWorkflowExportGateway(
            pendingRequest = { request -> pending.value = request },
            launchExport = { request -> launcher.launch(request.fileName.sanitizedJsonFileName()) },
        )
    }
}

private class AndroidWorkflowExportGateway(
    private val pendingRequest: (WorkflowExportRequest) -> Unit,
    private val launchExport: (WorkflowExportRequest) -> Unit,
) : PlatformWorkflowExportGateway {
    override fun exportJson(request: WorkflowExportRequest) {
        pendingRequest(request)
        launchExport(request)
    }
}

private fun Context.writeWorkflowExport(
    uri: Uri,
    request: WorkflowExportRequest,
): WorkflowExportResult {
    return try {
        val stream = contentResolver.openOutputStream(uri)
        if (stream == null) {
            tryDelete(uri)
            return WorkflowExportResult.Failed("Cannot open export destination")
        }
        stream.use { out ->
            out.write(request.json.encodeToByteArray())
            out.flush()
        }
        WorkflowExportResult.Success
    } catch (t: Throwable) {
        tryDelete(uri)
        WorkflowExportResult.Failed(t.message)
    }
}

private fun Context.tryDelete(uri: Uri) {
    try {
        contentResolver.delete(uri, null, null)
    } catch (_: Throwable) { /* best-effort cleanup */ }
}

private fun String.sanitizedJsonFileName(): String {
    val cleaned = map { char ->
        if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
    }.joinToString("").trim('_')
    val stem = cleaned.takeIf { it.isNotBlank() } ?: "workflow"
    return if (stem.endsWith(".json", ignoreCase = true)) stem else "$stem.json"
}
