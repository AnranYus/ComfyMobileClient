package com.comfymobile.data.importer

import androidx.compose.runtime.Composable
import com.comfymobile.data.workflow.WorkflowImportSource

/**
 * Platform-specific entry points normalize OS picker / share /
 * pasteboard payloads into this common shape. The common UI owns
 * parsing and persistence through WorkflowImporter.
 */
sealed interface PlatformWorkflowImportPayload {
    val source: WorkflowImportSource
    val sourceName: String?

    data class Text(
        val value: String,
        override val source: WorkflowImportSource,
        override val sourceName: String? = null,
    ) : PlatformWorkflowImportPayload

    data class Bytes(
        val value: ByteArray,
        override val source: WorkflowImportSource,
        override val sourceName: String? = null,
    ) : PlatformWorkflowImportPayload {
        override fun equals(other: Any?): Boolean =
            other is Bytes &&
                value.contentEquals(other.value) &&
                source == other.source &&
                sourceName == other.sourceName

        override fun hashCode(): Int {
            var result = value.contentHashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + sourceName.hashCode()
            return result
        }
    }
}

data class PlatformWorkflowImportFailure(
    val reason: PlatformWorkflowImportFailureReason,
    val detail: String? = null,
)

enum class PlatformWorkflowImportFailureReason {
    NoFileSelected,
    NoSharedPayload,
    ClipboardEmpty,
    UnsupportedClipboardPayload,
    UnableToReadPayload,
}

interface PlatformWorkflowImportGateway {
    fun pickFile()
    fun consumeSharedPayload()
    fun pasteText()
    fun pasteImage()
}

@Composable
expect fun rememberPlatformWorkflowImportGateway(
    onPayload: (PlatformWorkflowImportPayload) -> Unit,
    onFailure: (PlatformWorkflowImportFailure) -> Unit,
): PlatformWorkflowImportGateway
