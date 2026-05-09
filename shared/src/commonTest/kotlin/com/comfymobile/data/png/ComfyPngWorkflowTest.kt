package com.comfymobile.data.png

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Higher-level wrapper tests. The codec layer is exercised by
 * `PngTextChunkCodecTest`; here we only verify the JSON parsing /
 * serialisation surface and the keyword conventions.
 */
class ComfyPngWorkflowTest {

    @Test fun embedWorkflow_then_extractWorkflow_round_trips_json_object() {
        val png = minimalPng()
        val snapshot = buildJsonObject {
            put("nodes", JsonPrimitive(0)) // dummy value, type doesn't matter
            put("version", JsonPrimitive(0.4))
        }
        val embedded = ComfyPngWorkflow.embedWorkflow(png, snapshot)
        val recovered = ComfyPngWorkflow.extractWorkflow(embedded)
        assertNotNull(recovered)
        // Compare structurally rather than expecting byte identity, in
        // line with the structure-lossless contract from CONTEXT v4.
        assertEquals(JsonPrimitive(0), recovered["nodes"])
        assertEquals(0.4, recovered["version"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test fun embedPrompt_uses_prompt_keyword_and_does_not_collide_with_workflow() {
        val png = minimalPng()
        val workflow = buildJsonObject { put("kind", JsonPrimitive("ui")) }
        val prompt = buildJsonObject { put("kind", JsonPrimitive("api")) }
        var embedded = ComfyPngWorkflow.embedWorkflow(png, workflow)
        embedded = ComfyPngWorkflow.embedPrompt(embedded, prompt)
        val recoveredWorkflow = ComfyPngWorkflow.extractWorkflow(embedded)
        val recoveredPrompt = ComfyPngWorkflow.extractPrompt(embedded)
        assertEquals("ui", recoveredWorkflow?.get("kind")?.jsonPrimitive?.content)
        assertEquals("api", recoveredPrompt?.get("kind")?.jsonPrimitive?.content)
    }

    @Test fun extractWorkflow_returns_null_on_png_with_no_workflow_chunk() {
        val png = minimalPng()
        assertNull(ComfyPngWorkflow.extractWorkflow(png))
    }

    @Test fun keyword_constants_match_comfyui_convention() {
        // Pin the keyword strings so a future renaming would force the
        // test to be touched and reviewed against ComfyUI's wire format.
        assertEquals("workflow", ComfyPngWorkflow.KEYWORD_WORKFLOW)
        assertEquals("prompt", ComfyPngWorkflow.KEYWORD_PROMPT)
    }

    // ---------------------------------------------------------------- helpers

    /** Mirror of PngTextChunkCodecTest.buildMinimalPng so this test
     *  file stays self-contained. */
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
        signature.copyInto(out, off); off += signature.size
        ihdr.copyInto(out, off); off += ihdr.size
        idat.copyInto(out, off); off += idat.size
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
