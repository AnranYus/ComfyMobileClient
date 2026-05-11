package com.comfymobile.data.workflow

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.data.persistence.InMemoryWorkflowRepository
import com.comfymobile.data.png.ComfyPngWorkflow
import com.comfymobile.data.png.PngCrc32
import com.comfymobile.data.png.PngTextChunkCodec
import com.comfymobile.domain.workflow.WorkflowFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowImporterTest {

    @Test fun import_json_ui_workflow_persists_envelope_with_filename_label_and_stats() = runTest {
        val repository = InMemoryWorkflowRepository()
        val importer = importer(repository = repository, registry = registry())

        val outcome = importer.importJsonText(
            text = uiWorkflowJson(
                "KSampler",
                "CLIPTextEncode",
                "FancyCustomNode",
            ),
            source = WorkflowImportSource.File,
            sourceName = "/tmp/mobile-test.workflow.json",
        )

        val success = assertIs<WorkflowImportOutcome.Success>(outcome)
        assertEquals("mobile-test.workflow", success.row.displayName)
        assertEquals(WorkflowFormat.UI, success.row.envelope.format)
        assertEquals("file", success.row.envelope.metadata.source)
        assertEquals(3, success.nodeStats.totalNodes)
        assertEquals(2, success.nodeStats.supportedEditableNodes)
        assertEquals(1, success.nodeStats.unknownNodes)
        assertEquals(success.row, repository.listRecents().single())
    }

    @Test fun prepare_json_ui_workflow_does_not_persist_until_commit() = runTest {
        val repository = InMemoryWorkflowRepository()
        val importer = importer(repository = repository, registry = registry())

        val prepared = importer.prepareJsonText(
            text = uiWorkflowJson("KSampler"),
            source = WorkflowImportSource.File,
            sourceName = "preview.json",
        )

        val ready = assertIs<WorkflowImportPreparationOutcome.Ready>(prepared)
        assertEquals("preview", ready.draft.defaultDisplayName)
        assertEquals(emptyList(), repository.listRecents())

        val committed = importer.commit(ready.draft, displayName = "Edited name")

        assertEquals("Edited name", committed.row.displayName)
        assertEquals(committed.row, repository.listRecents().single())
    }

    @Test fun import_png_prefers_workflow_chunk_over_prompt_chunk() = runTest {
        val repository = InMemoryWorkflowRepository()
        val importer = importer(repository = repository)
        val pngWithBoth = PngTextChunkCodec.embed(
            png = PngTextChunkCodec.embed(
                png = minimalPng(),
                keyword = ComfyPngWorkflow.KEYWORD_WORKFLOW,
                text = uiWorkflowJson("KSampler"),
            ),
            keyword = ComfyPngWorkflow.KEYWORD_PROMPT,
            text = apiWorkflowJson("CLIPTextEncode"),
        )

        val outcome = importer.importPngBytes(
            bytes = pngWithBoth,
            source = WorkflowImportSource.PasteImage,
            sourceName = "from-gallery.png",
        )

        val success = assertIs<WorkflowImportOutcome.Success>(outcome)
        assertEquals(WorkflowFormat.UI, success.row.envelope.format)
        assertEquals("from-gallery", success.row.displayName)
        assertEquals("paste-image", success.row.envelope.metadata.source)
    }

    @Test fun import_png_uses_prompt_chunk_as_api_when_workflow_chunk_missing() = runTest {
        val importer = importer()
        val png = PngTextChunkCodec.embed(
            png = minimalPng(),
            keyword = ComfyPngWorkflow.KEYWORD_PROMPT,
            text = apiWorkflowJson("KSampler"),
        )

        val outcome = importer.importPngBytes(
            bytes = png,
            source = WorkflowImportSource.ShareSheet,
            sourceName = "api.png",
        )

        val success = assertIs<WorkflowImportOutcome.Success>(outcome)
        assertEquals(WorkflowFormat.API, success.row.envelope.format)
        assertEquals("share-sheet", success.row.envelope.metadata.source)
    }

    @Test fun import_png_falls_back_to_prompt_chunk_when_workflow_chunk_is_invalid() = runTest {
        val importer = importer()
        val png = PngTextChunkCodec.embed(
            png = PngTextChunkCodec.embed(
                png = minimalPng(),
                keyword = ComfyPngWorkflow.KEYWORD_WORKFLOW,
                text = "{ broken",
            ),
            keyword = ComfyPngWorkflow.KEYWORD_PROMPT,
            text = apiWorkflowJson("KSampler"),
        )

        val outcome = importer.importPngBytes(
            bytes = png,
            source = WorkflowImportSource.File,
            sourceName = "fallback.png",
        )

        val success = assertIs<WorkflowImportOutcome.Success>(outcome)
        assertEquals(WorkflowFormat.API, success.row.envelope.format)
    }

    @Test fun import_json_api_workflow_marks_api_format() = runTest {
        val importer = importer(registry = registry())

        val outcome = importer.importJsonText(
            text = apiWorkflowJson("KSampler", "CLIPTextEncode"),
            source = WorkflowImportSource.PasteText,
        )

        val success = assertIs<WorkflowImportOutcome.Success>(outcome)
        assertEquals(WorkflowFormat.API, success.row.envelope.format)
        assertEquals("paste-text", success.row.envelope.metadata.source)
        assertEquals(2, success.nodeStats.totalNodes)
        assertEquals(2, success.nodeStats.supportedEditableNodes)
        assertEquals(0, success.nodeStats.unknownNodes)
    }

    @Test fun import_png_without_workflow_or_prompt_returns_error() = runTest {
        val outcome = importer().importPngBytes(
            bytes = minimalPng(),
            source = WorkflowImportSource.File,
            sourceName = "empty.png",
        )

        assertEquals(
            WorkflowImportOutcome.Failure(WorkflowImportError.NoWorkflowInPng),
            outcome,
        )
    }

    @Test fun invalid_json_text_returns_error() = runTest {
        val outcome = importer().importJsonText(
            text = "{ nope",
            source = WorkflowImportSource.PasteText,
        )

        assertEquals(
            WorkflowImportOutcome.Failure(WorkflowImportError.InvalidJson(keyword = null)),
            outcome,
        )
    }

    @Test fun unsupported_json_shape_returns_error() = runTest {
        val outcome = importer().importJsonText(
            text = """{"hello":"world"}""",
            source = WorkflowImportSource.PasteText,
        )

        assertEquals(
            WorkflowImportOutcome.Failure(WorkflowImportError.UnsupportedJsonShape),
            outcome,
        )
    }

    @Test fun empty_ui_workflow_returns_error() = runTest {
        val outcome = importer().importJsonText(
            text = """{"nodes":[],"links":[]}""",
            source = WorkflowImportSource.PasteText,
        )

        assertEquals(
            WorkflowImportOutcome.Failure(WorkflowImportError.EmptyWorkflow),
            outcome,
        )
    }

    @Test fun large_file_and_majority_unknown_nodes_return_warnings() = runTest {
        val outcome = importer(
            registry = registry(),
            largeFileThresholdBytes = 10,
        ).importJsonText(
            text = uiWorkflowJson(
                "KSampler",
                "CustomA",
                "CustomB",
            ),
            source = WorkflowImportSource.File,
            sourceName = "large.json",
        )

        val success = assertIs<WorkflowImportOutcome.Success>(outcome)
        assertTrue(success.warnings.any { it is WorkflowImportWarning.LargeFile })
        assertTrue(success.warnings.any {
            it == WorkflowImportWarning.MostlyReadOnlyUnknownNodes(
                totalNodes = 3,
                unknownNodes = 2,
            )
        })
    }

    private fun importer(
        repository: InMemoryWorkflowRepository = InMemoryWorkflowRepository(),
        registry: NodeDescriptorRegistry? = null,
        largeFileThresholdBytes: Int = 2 * 1024 * 1024,
    ) = WorkflowImporter(
        repository = repository,
        nowEpochMs = { 1_700_000_000_000L },
        descriptorRegistry = registry,
        largeFileThresholdBytes = largeFileThresholdBytes,
    )

    private fun registry(): NodeDescriptorRegistry = NodeDescriptorRegistry.fromJson(
        """
        {
          "version": 1,
          "descriptors": [
            {
              "classType": "KSampler",
              "displayName": { "zh": "采样器", "en": "Sampler" },
              "category": "sampler",
              "editableParams": []
            },
            {
              "classType": "CLIPTextEncode",
              "displayName": { "zh": "提示词", "en": "Prompt" },
              "category": "prompt",
              "editableParams": []
            }
          ]
        }
        """.trimIndent()
    )

    private fun uiWorkflowJson(vararg classTypes: String): String =
        classTypes
            .mapIndexed { index, classType ->
                """{"id":${index + 1},"type":"$classType","inputs":[],"widgets_values":[]}"""
            }
            .joinToString(prefix = """{"nodes":[""", separator = ",", postfix = """],"links":[],"version":0.4}""")

    private fun apiWorkflowJson(vararg classTypes: String): String =
        classTypes
            .mapIndexed { index, classType ->
                """"${index + 1}":{"class_type":"$classType","inputs":{}}"""
            }
            .joinToString(prefix = "{", separator = ",", postfix = "}")

    private fun minimalPng(): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val ihdr = encodeChunk(
            "IHDR".encodeToByteArray(),
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0),
        )
        val idat = encodeChunk("IDAT".encodeToByteArray(), byteArrayOf(0))
        val iend = encodeChunk("IEND".encodeToByteArray(), byteArrayOf())
        val out = ByteArray(signature.size + ihdr.size + idat.size + iend.size)
        var off = 0
        signature.copyInto(out, off)
        off += signature.size
        ihdr.copyInto(out, off)
        off += ihdr.size
        idat.copyInto(out, off)
        off += idat.size
        iend.copyInto(out, off)
        return out
    }

    private fun encodeChunk(type: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(4 + 4 + data.size + 4)
        val len = data.size
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        type.copyInto(out, destinationOffset = 4)
        data.copyInto(out, destinationOffset = 8)
        val crc = PngCrc32.compute(typeBytes = type, dataBytes = data)
        val cOff = 8 + data.size
        out[cOff] = (crc ushr 24).toByte()
        out[cOff + 1] = (crc ushr 16).toByte()
        out[cOff + 2] = (crc ushr 8).toByte()
        out[cOff + 3] = crc.toByte()
        return out
    }
}
