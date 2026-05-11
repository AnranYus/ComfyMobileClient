package com.comfymobile.presentation.graph

import com.comfymobile.domain.workflow.WorkflowGraph
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lifts the structure-lossless [WorkflowGraph.Ui.raw] JSON into a
 * typed [ParsedUiGraph] for the render layer. Pure function — no IO,
 * no clock, no side effects; **safe to call on any thread**.
 *
 * The ComfyUI editor save format (per
 * `docs/architecture/T0.1-comfyui-integration.md` §3) is roughly:
 *
 * ```json
 * {
 *   "nodes": [
 *     {
 *       "id": 1,
 *       "type": "CheckpointLoaderSimple",
 *       "title": "checkpoint",
 *       "pos": [100, 200],
 *       "size": {"0": 320, "1": 100},
 *       "inputs":  [{ "name": "model_path", "type": "MODEL", "link": null }],
 *       "outputs": [{ "name": "MODEL",       "type": "MODEL", "links": [1] }]
 *     }
 *   ],
 *   "links": [
 *     [1, 1, 0, 3, 0, "MODEL"]
 *   ]
 * }
 * ```
 *
 * The parser is deliberately tolerant of older / partial saves:
 *  - missing `pos` / `size` → [ParsedNode.originalPos] / .originalSize is null,
 *    layout substitutes defaults.
 *  - missing `inputs` / `outputs` → empty lists; render layer draws no ports.
 *  - missing `title` → null; render layer falls back to descriptor display name
 *    or class type.
 *  - link tuple element types other than the canonical
 *    `[int, int, int, int, int, string]` → entry is dropped (so a
 *    malformed link can't sink the entire workflow's render).
 *
 * Anything we don't recognise stays in the original
 * [WorkflowGraph.Ui.raw] JSON; the parser does NOT mutate or strip
 * it. Round-trip safety is the envelope's responsibility.
 */
object UiGraphParser {

    /** Top-level JSON keys. */
    private const val KEY_NODES = "nodes"
    private const val KEY_LINKS = "links"

    /** Per-node JSON keys. */
    private const val NODE_KEY_ID = "id"
    private const val NODE_KEY_TYPE = "type"
    private const val NODE_KEY_TITLE = "title"
    private const val NODE_KEY_POS = "pos"
    private const val NODE_KEY_SIZE = "size"
    private const val NODE_KEY_INPUTS = "inputs"
    private const val NODE_KEY_OUTPUTS = "outputs"
    private const val NODE_KEY_WIDGETS_VALUES = "widgets_values"

    /** Per-port JSON keys. */
    private const val PORT_KEY_NAME = "name"
    private const val PORT_KEY_TYPE = "type"

    fun parse(graph: WorkflowGraph.Ui): ParsedUiGraph {
        val raw = graph.raw
        val nodes = raw[KEY_NODES]?.asJsonArrayOrNull()?.let { array ->
            array.mapNotNull { it.asJsonObjectOrNull()?.let(::parseNode) }
        } ?: emptyList()
        val links = raw[KEY_LINKS]?.asJsonArrayOrNull()?.let { array ->
            array.mapNotNull { it.asJsonArrayOrNull()?.let(::parseLink) }
        } ?: emptyList()
        return ParsedUiGraph(nodes = nodes, links = links)
    }

    // ---------------------------------------------------------------- nodes

    private fun parseNode(obj: JsonObject): ParsedNode? {
        val id = obj[NODE_KEY_ID]?.asLooseStringOrNull() ?: return null
        val classType = obj[NODE_KEY_TYPE]?.asStringOrNull() ?: return null
        return ParsedNode(
            id = id,
            classType = classType,
            title = obj[NODE_KEY_TITLE]?.asStringOrNull(),
            originalPos = obj[NODE_KEY_POS]?.asPositionOrNull(),
            originalSize = obj[NODE_KEY_SIZE]?.asSizeOrNull(),
            inputs = obj[NODE_KEY_INPUTS]?.asJsonArrayOrNull()?.parsePorts() ?: emptyList(),
            outputs = obj[NODE_KEY_OUTPUTS]?.asJsonArrayOrNull()?.parsePorts() ?: emptyList(),
            // `widgets_values` is an ordered JSON array; preserve the
            // raw JsonElement per slot so the render layer can format
            // each value according to the matching ParamDescriptor's
            // ControlType (Slider → "0.7", MultilineText → "a beauti…").
            widgetsValues = obj[NODE_KEY_WIDGETS_VALUES]?.asJsonArrayOrNull()?.toList() ?: emptyList(),
        )
    }

    private fun JsonArray.parsePorts(): List<NodePort> = mapIndexedNotNull { idx, element ->
        val obj = element.asJsonObjectOrNull() ?: return@mapIndexedNotNull null
        NodePort(
            slotIndex = idx,
            name = obj[PORT_KEY_NAME]?.asStringOrNull() ?: "",
            type = obj[PORT_KEY_TYPE]?.asStringOrNull() ?: UNKNOWN_TYPE,
        )
    }

    // ---------------------------------------------------------------- links

    /**
     * ComfyUI link tuple shape:
     * `[link_id, source_node_id, source_slot, target_node_id, target_slot, type]`.
     */
    private fun parseLink(arr: JsonArray): ParsedLink? {
        if (arr.size < 6) return null
        val linkId = arr[0].asLooseStringOrNull() ?: return null
        val sourceNodeId = arr[1].asLooseStringOrNull() ?: return null
        val sourceSlot = arr[2].asIntOrNull() ?: return null
        val targetNodeId = arr[3].asLooseStringOrNull() ?: return null
        val targetSlot = arr[4].asIntOrNull() ?: return null
        val type = arr[5].asStringOrNull() ?: UNKNOWN_TYPE
        return ParsedLink(
            linkId = linkId,
            sourceNodeId = sourceNodeId,
            sourceSlot = sourceSlot,
            targetNodeId = targetNodeId,
            targetSlot = targetSlot,
            type = type,
        )
    }

    // ---------------------------------------------------------------- json helpers

    /** Sentinel link type for ports / links missing the type field. */
    private const val UNKNOWN_TYPE = "UNKNOWN"

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /**
     * Accept either a JSON string or a JSON number for fields that
     * conceptually identify an entity (node id, link id) — ComfyUI
     * sometimes serialises them numerically, sometimes as strings.
     */
    private fun JsonElement.asLooseStringOrNull(): String? = when (this) {
        is JsonPrimitive ->
            if (isString) contentOrNull
            else longOrNull?.toString() ?: doubleOrNull?.toLong()?.toString()
        is JsonNull -> null
        else -> null
    }

    private fun JsonElement.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.intOrNull
            ?: (this as? JsonPrimitive)?.doubleOrNull?.toInt()

    /**
     * `pos` is `[x, y]` (array). Older saves occasionally use
     * `{"0": x, "1": y}` (sparse object). Accept both.
     */
    private fun JsonElement.asPositionOrNull(): Position? = when (this) {
        is JsonArray -> {
            val x = getOrNull(0)?.asFloatOrNull()
            val y = getOrNull(1)?.asFloatOrNull()
            if (x != null && y != null) Position(x, y) else null
        }
        is JsonObject -> {
            val x = this["0"]?.asFloatOrNull()
            val y = this["1"]?.asFloatOrNull()
            if (x != null && y != null) Position(x, y) else null
        }
        else -> null
    }

    /**
     * `size` is canonically `{"0": width, "1": height}` in editor
     * saves. Some custom-node forks emit `[width, height]`. Accept both.
     */
    private fun JsonElement.asSizeOrNull(): Size? = when (this) {
        is JsonObject -> {
            val w = this["0"]?.asFloatOrNull()
            val h = this["1"]?.asFloatOrNull()
            if (w != null && h != null) Size(w, h) else null
        }
        is JsonArray -> {
            val w = getOrNull(0)?.asFloatOrNull()
            val h = getOrNull(1)?.asFloatOrNull()
            if (w != null && h != null) Size(w, h) else null
        }
        else -> null
    }

    private fun JsonElement.asFloatOrNull(): Float? =
        (this as? JsonPrimitive)?.floatOrNull
            ?: (this as? JsonPrimitive)?.doubleOrNull?.toFloat()

    @Suppress("unused")
    private fun JsonElement.asBooleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.booleanOrNull
}
