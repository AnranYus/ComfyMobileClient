package com.comfymobile.data.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.comfymobile.data.workflow.WorkflowImportSource

@Composable
actual fun rememberPlatformWorkflowImportGateway(
    onPayload: (PlatformWorkflowImportPayload) -> Unit,
    onFailure: (PlatformWorkflowImportFailure) -> Unit,
): PlatformWorkflowImportGateway {
    val context = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.NoFileSelected))
        } else {
            context.readImportUri(uri, WorkflowImportSource.File, onPayload, onFailure)
        }
    }

    return remember(context, fileLauncher, onPayload, onFailure) {
        AndroidWorkflowImportGateway(
            context = context,
            launchFilePicker = { fileLauncher.launch("*/*") },
            onPayload = onPayload,
            onFailure = onFailure,
        )
    }
}

private class AndroidWorkflowImportGateway(
    private val context: Context,
    private val launchFilePicker: () -> Unit,
    private val onPayload: (PlatformWorkflowImportPayload) -> Unit,
    private val onFailure: (PlatformWorkflowImportFailure) -> Unit,
) : PlatformWorkflowImportGateway {

    override fun pickFile() {
        launchFilePicker()
    }

    override fun consumeSharedPayload() {
        val intent = AndroidWorkflowImportInbox.consume()
        if (intent == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.NoSharedPayload))
            return
        }
        val consumed = when (intent.action) {
            Intent.ACTION_SEND -> consumeSendIntent(intent)
            Intent.ACTION_VIEW -> consumeViewIntent(intent)
            else -> false
        }
        if (!consumed) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.NoSharedPayload))
        }
    }

    override fun pasteText() {
        val clip = context.clipboard()?.primaryClip
        val text = clip?.firstText()
        if (text.isNullOrBlank()) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.ClipboardEmpty))
        } else {
            onPayload(
                PlatformWorkflowImportPayload.Text(
                    value = text,
                    source = WorkflowImportSource.PasteText,
                )
            )
        }
    }

    override fun pasteImage() {
        val uri = context.clipboard()?.primaryClip?.firstUri()
        if (uri == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.UnsupportedClipboardPayload))
        } else {
            context.readImportUri(uri, WorkflowImportSource.PasteImage, onPayload, onFailure)
        }
    }

    private fun consumeSendIntent(intent: Intent): Boolean {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            onPayload(
                PlatformWorkflowImportPayload.Text(
                    value = text,
                    source = WorkflowImportSource.ShareSheet,
                )
            )
            return true
        }
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return false
        context.readImportUri(stream, WorkflowImportSource.ShareSheet, onPayload, onFailure)
        return true
    }

    private fun consumeViewIntent(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        context.readImportUri(uri, WorkflowImportSource.ShareSheet, onPayload, onFailure)
        return true
    }
}

object AndroidWorkflowImportInbox {
    private val pending = ArrayDeque<Intent>()

    fun enqueue(intent: Intent?) {
        if (intent?.isImportIntent() != true) return
        pending.addLast(Intent(intent))
    }

    fun consume(): Intent? {
        if (pending.isEmpty()) return null
        return pending.removeFirst()
    }

    private fun Intent.isImportIntent(): Boolean =
        action == Intent.ACTION_SEND || action == Intent.ACTION_VIEW
}

private fun Context.readImportUri(
    uri: Uri,
    source: WorkflowImportSource,
    onPayload: (PlatformWorkflowImportPayload) -> Unit,
    onFailure: (PlatformWorkflowImportFailure) -> Unit,
) {
    val name = displayName(uri)
    val bytes = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        onFailure(
            PlatformWorkflowImportFailure(
                reason = PlatformWorkflowImportFailureReason.UnableToReadPayload,
                detail = e.message,
            )
        )
        return
    }
    if (bytes == null) {
        onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.UnableToReadPayload))
        return
    }

    val mime = contentResolver.getType(uri).orEmpty()
    val lowerName = name.orEmpty().lowercase()
    if (mime.contains("json") || lowerName.endsWith(".json")) {
        onPayload(
            PlatformWorkflowImportPayload.Text(
                value = bytes.decodeToString(),
                source = source,
                sourceName = name,
            )
        )
    } else {
        onPayload(
            PlatformWorkflowImportPayload.Bytes(
                value = bytes,
                source = source,
                sourceName = name,
            )
        )
    }
}

private fun Context.displayName(uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment

private fun Context.clipboard(): ClipboardManager? =
    getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

private fun ClipData.firstText(): String? {
    if (itemCount <= 0) return null
    return getItemAt(0).text?.toString()
}

private fun ClipData.firstUri(): Uri? {
    if (itemCount <= 0) return null
    return getItemAt(0).uri
}
