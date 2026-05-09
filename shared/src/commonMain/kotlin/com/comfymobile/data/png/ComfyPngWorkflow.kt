package com.comfymobile.data.png

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * High-level helpers around [PngTextChunkCodec] that pair the
 * ComfyUI conventions with kotlinx-serialization JSON parsing.
 *
 * Two keywords ComfyUI ships:
 *   - `workflow` — UI-format workflow JSON
 *   - `prompt`   — API-format prompt JSON
 *
 * **Critical contract** (per ADR-0003 / @Lily PR #6 review msg
 * `7bd63c37`): the value embedded under `workflow` MUST be the
 * **current edited UI snapshot at submit time**, not the original
 * imported document. If we embed the original, a recipient who
 * drags the generated PNG back into desktop ComfyUI sees the
 * un-edited workflow rather than what actually produced the image.
 * Callers in T1.4 are responsible for building the right snapshot
 * before calling [embedWorkflow]; this module just writes whatever
 * JSON it's given.
 *
 * Currently only "UI-format imports → write back UI" is supported.
 * If the user originally imported only an API-format document, a
 * synthetic UI snapshot needs to be derived first; that synthesis
 * lives in T1.4 (UI shell) and is out of scope for this PR.
 */
object ComfyPngWorkflow {

    const val KEYWORD_WORKFLOW: String = "workflow"
    const val KEYWORD_PROMPT: String = "prompt"

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * Embed the **current edited UI snapshot** under the `workflow`
     * keyword. Replaces any existing `workflow` chunk.
     *
     * @param png the source PNG bytes (typically just-fetched output
     *            from `GET /view`).
     * @param uiWorkflowSnapshot the JSON value to embed — caller's
     *            responsibility to ensure this reflects the latest
     *            edits, not the originally imported workflow.
     */
    fun embedWorkflow(png: ByteArray, uiWorkflowSnapshot: JsonElement): ByteArray =
        PngTextChunkCodec.embed(
            png = png,
            keyword = KEYWORD_WORKFLOW,
            text = json.encodeToString(JsonElement.serializer(), uiWorkflowSnapshot),
        )

    /**
     * Embed the API-format prompt under the `prompt` keyword. This
     * mirrors what ComfyUI does so the embedded data is symmetric
     * with the desktop tooling.
     */
    fun embedPrompt(png: ByteArray, apiPrompt: JsonElement): ByteArray =
        PngTextChunkCodec.embed(
            png = png,
            keyword = KEYWORD_PROMPT,
            text = json.encodeToString(JsonElement.serializer(), apiPrompt),
        )

    /**
     * Read the `workflow` text chunk and parse it as JSON. Returns
     * null if no such chunk exists; throws if the chunk text is not
     * valid JSON.
     */
    fun extractWorkflow(png: ByteArray): JsonObject? {
        val text = PngTextChunkCodec.extract(png, KEYWORD_WORKFLOW) ?: return null
        return json.parseToJsonElement(text) as? JsonObject
    }

    /** Read the `prompt` text chunk. */
    fun extractPrompt(png: ByteArray): JsonObject? {
        val text = PngTextChunkCodec.extract(png, KEYWORD_PROMPT) ?: return null
        return json.parseToJsonElement(text) as? JsonObject
    }
}
