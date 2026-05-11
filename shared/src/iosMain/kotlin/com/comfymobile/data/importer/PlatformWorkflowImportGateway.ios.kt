package com.comfymobile.data.importer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.comfymobile.data.workflow.WorkflowImportSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun rememberPlatformWorkflowImportGateway(
    onPayload: (PlatformWorkflowImportPayload) -> Unit,
    onFailure: (PlatformWorkflowImportFailure) -> Unit,
): PlatformWorkflowImportGateway =
    remember(onPayload, onFailure) {
        IosWorkflowImportGateway(
            onPayload = onPayload,
            onFailure = onFailure,
        )
    }

private class IosWorkflowImportGateway(
    private val onPayload: (PlatformWorkflowImportPayload) -> Unit,
    private val onFailure: (PlatformWorkflowImportFailure) -> Unit,
) : PlatformWorkflowImportGateway {

    private var pickerDelegate: PickerDelegate? = null

    override fun pickFile() {
        val controller = rootViewController()
        if (controller == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.UnableToReadPayload))
            return
        }

        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.json", "public.png", "public.image"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        val delegate = PickerDelegate(
            onPicked = { url -> readUrl(url, WorkflowImportSource.File) },
            onCancelled = {
                onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.NoFileSelected))
            },
        )
        pickerDelegate = delegate
        picker.delegate = delegate
        controller.presentViewController(picker, animated = true, completion = null)
    }

    override fun consumeSharedPayload() {
        val url = consumeIosWorkflowImportUrl()
        if (url == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.NoSharedPayload))
        } else {
            readUrl(url, WorkflowImportSource.ShareSheet)
        }
    }

    override fun pasteText() {
        val text = UIPasteboard.generalPasteboard.string
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
        val image = UIPasteboard.generalPasteboard.image
        val data = image?.let { UIImagePNGRepresentation(it) }
        if (data == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.UnsupportedClipboardPayload))
        } else {
            onPayload(
                PlatformWorkflowImportPayload.Bytes(
                    value = data.toByteArray(),
                    source = WorkflowImportSource.PasteImage,
                    sourceName = "clipboard.png",
                )
            )
        }
    }

    private fun readUrl(url: NSURL, source: WorkflowImportSource) {
        val path = url.path
        val data = path?.let { NSFileManager.defaultManager.contentsAtPath(it) }
        if (data == null) {
            onFailure(PlatformWorkflowImportFailure(PlatformWorkflowImportFailureReason.UnableToReadPayload))
            return
        }
        val name = url.lastPathComponent
        val bytes = data.toByteArray()
        if (name?.lowercase()?.endsWith(".json") == true) {
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
}

private class PickerDelegate(
    private val onPicked: (NSURL) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onCancelled()
        } else {
            onPicked(url)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}

private fun rootViewController(): UIViewController? =
    UIApplication.sharedApplication.keyWindow?.rootViewController

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size == 0) return out
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
