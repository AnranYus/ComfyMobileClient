package com.comfymobile.data.workflow

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.data.png.ComfyPngWorkflow
import com.comfymobile.data.png.PngFormatException
import com.comfymobile.data.png.PngTextChunkCodec
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Common importer core for T2.0. Platform entry points (file picker,
 * share sheet, pasteboard) normalize their payload into text or PNG
 * bytes and call this class.
 */
class WorkflowImporter(
    private val repository: WorkflowRepository,
    private val nowEpochMs: () -> Long,
    private val descriptorRegistry: NodeDescriptorRegistry? = null,
    private val largeFileThresholdBytes: Int = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
) {

    suspend fun importJsonText(
        text: String,
        source: WorkflowImportSource,
        sourceName: String? = null,
    ): WorkflowImportOutcome =
        parseJsonObject(text)?.let { parsed ->
            persist(
                original = parsed,
                format = detectFormat(parsed) ?: return WorkflowImportOutcome.Failure(
                    WorkflowImportError.UnsupportedJsonShape,
                ),
                source = source,
                sourceName = sourceName,
                sizeBytes = text.encodeToByteArray().size,
            )
        } ?: WorkflowImportOutcome.Failure(
            WorkflowImportError.InvalidJson(keyword = null),
        )

    suspend fun importPngBytes(
        bytes: ByteArray,
        source: WorkflowImportSource,
        sourceName: String? = null,
    ): WorkflowImportOutcome {
        val workflowText = try {
            PngTextChunkCodec.extract(bytes, ComfyPngWorkflow.KEYWORD_WORKFLOW)
        } catch (e: PngFormatException) {
            return WorkflowImportOutcome.Failure(WorkflowImportError.InvalidPng(e.message.orEmpty()))
        }

        var workflowChunkWasInvalid = false
        if (workflowText != null) {
            val parsed = parseJsonObject(workflowText)
            if (parsed != null) {
                return persist(
                    original = parsed,
                    format = WorkflowFormat.UI,
                    source = source,
                    sourceName = sourceName,
                    sizeBytes = bytes.size,
                )
            }
            workflowChunkWasInvalid = true
        }

        val promptText = try {
            PngTextChunkCodec.extract(bytes, ComfyPngWorkflow.KEYWORD_PROMPT)
        } catch (e: PngFormatException) {
            return WorkflowImportOutcome.Failure(WorkflowImportError.InvalidPng(e.message.orEmpty()))
        } ?: return WorkflowImportOutcome.Failure(
            if (workflowChunkWasInvalid) {
                WorkflowImportError.InvalidJson(ComfyPngWorkflow.KEYWORD_WORKFLOW)
            } else {
                WorkflowImportError.NoWorkflowInPng
            }
        )

        val parsed = parseJsonObject(promptText)
            ?: return WorkflowImportOutcome.Failure(
                WorkflowImportError.InvalidJson(ComfyPngWorkflow.KEYWORD_PROMPT),
            )
        return persist(
            original = parsed,
            format = WorkflowFormat.API,
            source = source,
            sourceName = sourceName,
            sizeBytes = bytes.size,
        )
    }

    private suspend fun persist(
        original: JsonObject,
        format: WorkflowFormat,
        source: WorkflowImportSource,
        sourceName: String?,
        sizeBytes: Int,
    ): WorkflowImportOutcome {
        val nodeStats = nodeStats(original, format)
        if (nodeStats.totalNodes == 0) {
            return WorkflowImportOutcome.Failure(WorkflowImportError.EmptyWorkflow)
        }
        val warnings = buildList {
            if (sizeBytes > largeFileThresholdBytes) {
                add(WorkflowImportWarning.LargeFile(sizeBytes, largeFileThresholdBytes))
            }
            if (nodeStats.unknownNodes * 2 > nodeStats.totalNodes) {
                add(
                    WorkflowImportWarning.MostlyReadOnlyUnknownNodes(
                        totalNodes = nodeStats.totalNodes,
                        unknownNodes = nodeStats.unknownNodes,
                    )
                )
            }
        }
        val now = nowEpochMs()
        val envelope = WorkflowEnvelope(
            original = original,
            format = format,
            metadata = WorkflowMetadata(
                label = displayNameFor(sourceName),
                createdAtEpochMs = now,
                lastEditedAtEpochMs = now,
                source = source.metadataSource,
            ),
        )
        return WorkflowImportOutcome.Success(
            row = repository.upsert(envelope),
            warnings = warnings,
            nodeStats = nodeStats,
        )
    }

    private fun parseJsonObject(text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun detectFormat(obj: JsonObject): WorkflowFormat? {
        if (obj["nodes"] is JsonArray) return WorkflowFormat.UI
        if (obj.values.any { value ->
                val node = value as? JsonObject
                (node?.get("class_type") as? JsonPrimitive)?.contentOrNull != null
            }
        ) {
            return WorkflowFormat.API
        }
        return null
    }

    private fun nodeStats(
        original: JsonObject,
        format: WorkflowFormat,
    ): WorkflowImportNodeStats {
        val classTypes = when (format) {
            WorkflowFormat.UI -> uiClassTypes(original)
            WorkflowFormat.API -> apiClassTypes(original)
        }
        val knownClassTypes = descriptorRegistry?.knownClassTypes.orEmpty()
        val supportedCount = if (knownClassTypes.isEmpty()) {
            0
        } else {
            classTypes.count { it in knownClassTypes }
        }
        return WorkflowImportNodeStats(
            totalNodes = classTypes.size,
            supportedEditableNodes = supportedCount,
            unknownNodes = if (knownClassTypes.isEmpty()) 0 else classTypes.size - supportedCount,
        )
    }

    private fun uiClassTypes(original: JsonObject): List<String> {
        val nodes = original["nodes"] as? JsonArray ?: return emptyList()
        return nodes.mapNotNull { node ->
            (node as? JsonObject)
                ?.get("type")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
        }
    }

    private fun apiClassTypes(original: JsonObject): List<String> =
        original.values.mapNotNull { node ->
            (node as? JsonObject)
                ?.get("class_type")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
        }

    private fun displayNameFor(sourceName: String?): String {
        val fileName = sourceName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val trimmed = if ('.' in fileName) fileName.substringBeforeLast('.') else fileName
        return trimmed.ifEmpty { DEFAULT_WORKFLOW_LABEL }
    }

    private companion object {
        const val DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 2 * 1024 * 1024
        const val DEFAULT_WORKFLOW_LABEL = "Imported workflow"
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}

enum class WorkflowImportSource(val metadataSource: String) {
    File("file"),
    ShareSheet("share-sheet"),
    PasteText("paste-text"),
    PasteImage("paste-image"),
}

sealed interface WorkflowImportOutcome {
    data class Success(
        val row: WorkflowRow,
        val nodeStats: WorkflowImportNodeStats,
        val warnings: List<WorkflowImportWarning> = emptyList(),
    ) : WorkflowImportOutcome

    data class Failure(val error: WorkflowImportError) : WorkflowImportOutcome
}

data class WorkflowImportNodeStats(
    val totalNodes: Int,
    val supportedEditableNodes: Int,
    val unknownNodes: Int,
)

sealed interface WorkflowImportWarning {
    data class LargeFile(
        val sizeBytes: Int,
        val thresholdBytes: Int,
    ) : WorkflowImportWarning

    data class MostlyReadOnlyUnknownNodes(
        val totalNodes: Int,
        val unknownNodes: Int,
    ) : WorkflowImportWarning
}

sealed interface WorkflowImportError {
    data class InvalidJson(val keyword: String?) : WorkflowImportError
    data class InvalidPng(val reason: String) : WorkflowImportError
    data object NoWorkflowInPng : WorkflowImportError
    data object UnsupportedJsonShape : WorkflowImportError
    data object EmptyWorkflow : WorkflowImportError
}
