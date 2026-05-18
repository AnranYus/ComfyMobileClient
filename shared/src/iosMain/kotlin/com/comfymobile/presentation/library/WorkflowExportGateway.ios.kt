package com.comfymobile.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@Composable
actual fun rememberPlatformWorkflowExportGateway(
    onResult: (WorkflowExportRequest, WorkflowExportResult) -> Unit,
): PlatformWorkflowExportGateway =
    remember(onResult) {
        IosWorkflowExportGateway(onResult = onResult)
    }

private class IosWorkflowExportGateway(
    private val onResult: (WorkflowExportRequest, WorkflowExportResult) -> Unit,
) : PlatformWorkflowExportGateway {
    private var pickerDelegate: ExportPickerDelegate? = null

    override fun exportJson(request: WorkflowExportRequest) {
        val controller = rootViewController()
        if (controller == null) {
            onResult(request, WorkflowExportResult.Unsupported)
            return
        }
        val url = writeExportFile(request)
        if (url == null) {
            onResult(request, WorkflowExportResult.Failed("Unable to write export file"))
            return
        }
        val picker = UIDocumentPickerViewController(
            URL = url,
            inMode = UIDocumentPickerMode.UIDocumentPickerModeExportToService,
        )
        val delegate = ExportPickerDelegate(
            onPicked = {
                pickerDelegate = null
                onResult(request, WorkflowExportResult.Success)
            },
            onCancelled = {
                pickerDelegate = null
                onResult(request, WorkflowExportResult.Cancelled)
            },
        )
        pickerDelegate = delegate
        picker.delegate = delegate
        controller.presentViewController(picker, animated = true, completion = null)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeExportFile(request: WorkflowExportRequest): NSURL? {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/comfy-workflow-export"
        val directoryReady = NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!directoryReady) return null
        val path = "$directory/${request.fileName.sanitizedJsonFileName()}"
        return if (request.json.encodeToByteArray().writeToPath(path)) {
            NSURL.fileURLWithPath(path)
        } else {
            null
        }
    }
}

private class ExportPickerDelegate(
    private val onPicked: () -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onPicked()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}

private fun rootViewController(): UIViewController? =
    UIApplication.sharedApplication.keyWindow?.rootViewController

private fun String.sanitizedJsonFileName(): String {
    val cleaned = map { char ->
        if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
    }.joinToString("").trim('_')
    val stem = cleaned.takeIf { it.isNotBlank() } ?: "workflow"
    return if (stem.endsWith(".json", ignoreCase = true)) stem else "$stem.json"
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.writeToPath(path: String): Boolean {
    val file = fopen(path, "wb") ?: return false
    return try {
        if (isEmpty()) {
            true
        } else {
            val written = usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1UL, size.toULong(), file)
            }
            written == size.toULong()
        }
    } finally {
        fclose(file)
    }
}
