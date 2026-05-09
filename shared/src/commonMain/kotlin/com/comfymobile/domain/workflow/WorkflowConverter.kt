package com.comfymobile.domain.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI ↔ API conversion for ComfyUI workflows.
 *
 * The UI form (full editor save) carries position metadata,
 * `widgets_values` arrays and link tuples; the API form is the flat
 * `{ node_id: { class_type, inputs } }` structure `POST /prompt`
 * accepts. See `docs/architecture/T0.1-comfyui-integration.md` §3
 * for full details.
 *
 * `widgets_values` ordering follows ComfyUI's webui rules: each
 * primitive-typed entry in `INPUT_TYPES.required` becomes a widget,
 * appended in declaration order. Some types insert auxiliary
 * widgets (notably `INT` for `seed` adds a `control_after_generate`
 * combo). The converter resolves the order via:
 *   1. A hard-coded whitelist for the descriptor classTypes (most
 *      reliable; includes UI-only auxiliary widgets).
 *   2. `/object_info[classType].input.required` declaration order
 *      restricted to primitive types (best effort for unknown
 *      classTypes).
 *
 * UI-only widgets (`control_after_generate`, `prompt_after_generate`,
 * etc.) appear in the hardcoded whitelist ordering but are filtered
 * out before writing to API `inputs`, since the server doesn't
 * accept them.
 */
class WorkflowConverter {

    /**
     * Convert a UI-format workflow into the matching API graph.
     *
     * @param ui the imported UI workflow. Caller is responsible for
     *           verifying the structure has `nodes[]` and (optionally)
     *           `links[]`.
     * @param objectInfo cached `/object_info` payload (top-level shape:
     *           `{ "<classType>": { "input": { "required": {...} } } }`).
     *           Optional; when null only whitelist classTypes are
     *           handled cleanly. Unknown classTypes get an empty
     *           inputs map (callers should treat this as "node
     *           survives but its widget values were dropped").
     */
    fun uiToApi(ui: WorkflowGraph.Ui, objectInfo: JsonElement? = null): WorkflowGraph.Api {
        val uiObj = ui.raw
        val linksArray = uiObj["links"] as? JsonArray
        val linkMap = parseLinks(linksArray)

        val nodes = (uiObj["nodes"] as? JsonArray) ?: return WorkflowGraph.Api(emptyMap())
        val apiNodes = LinkedHashMap<String, ApiNode>(nodes.size)

        for (uiNode in nodes) {
            val node = uiNode as? JsonObject ?: continue
            val nodeId = node["id"]?.jsonPrimitive?.intOrNull?.toString() ?: continue
            val classType = node["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val inputs = LinkedHashMap<String, JsonElement>()

            // 1. Wired inputs (the `inputs[]` array entries that have
            //    a non-null `link`). Translate to API `[src_id, slot]`
            //    tuples.
            (node["inputs"] as? JsonArray)?.forEach { input ->
                val inputObj = input as? JsonObject ?: return@forEach
                val inputName = inputObj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val linkIdElement = inputObj["link"] ?: return@forEach
                if (linkIdElement is JsonPrimitive && linkIdElement.contentOrNull == null) return@forEach
                val linkId = linkIdElement.jsonPrimitive.intOrNull ?: return@forEach
                val link = linkMap[linkId] ?: return@forEach
                inputs[inputName] = buildJsonArray {
                    add(JsonPrimitive(link.sourceNodeId))
                    add(JsonPrimitive(link.sourceSlot))
                }
            }

            // 2. Widget values — pop from `widgets_values[]` in widget
            //    declaration order. Hard-coded whitelist takes
            //    precedence; unknowns fall back to /object_info; if
            //    neither is available, widget values are skipped
            //    entirely.
            val widgetsValues = (node["widgets_values"] as? JsonArray)?.toList() ?: emptyList()
            val widgetOrder = resolveWidgetOrder(classType, objectInfo)
            for ((index, widgetName) in widgetOrder.withIndex()) {
                if (index >= widgetsValues.size) break
                if (widgetName in UI_ONLY_WIDGETS) continue
                inputs[widgetName] = widgetsValues[index]
            }

            apiNodes[nodeId] = ApiNode(classType = classType, inputs = inputs)
        }
        return WorkflowGraph.Api(nodes = apiNodes)
    }

    /** Internal link descriptor parsed from `links[]`. */
    private data class LinkInfo(
        val sourceNodeId: String,
        val sourceSlot: Int,
    )

    private fun parseLinks(links: JsonArray?): Map<Int, LinkInfo> {
        if (links == null) return emptyMap()
        // Each link is encoded as a JSON array:
        //   [link_id, src_node_id, src_slot, dst_node_id, dst_slot, type]
        val result = HashMap<Int, LinkInfo>(links.size)
        for (entry in links) {
            val arr = entry as? JsonArray ?: continue
            if (arr.size < 4) continue
            val linkId = (arr[0] as? JsonPrimitive)?.intOrNull ?: continue
            val srcNodeId = (arr[1] as? JsonPrimitive)?.intOrNull?.toString() ?: continue
            val srcSlot = (arr[2] as? JsonPrimitive)?.intOrNull ?: continue
            result[linkId] = LinkInfo(sourceNodeId = srcNodeId, sourceSlot = srcSlot)
        }
        return result
    }

    private fun resolveWidgetOrder(classType: String, objectInfo: JsonElement?): List<String> {
        WHITELIST_WIDGET_ORDER[classType]?.let { return it }
        if (objectInfo == null) return emptyList()
        val info = (objectInfo as? JsonObject) ?: return emptyList()
        val classInfo = (info[classType] as? JsonObject) ?: return emptyList()
        val required = (classInfo["input"] as? JsonObject)?.get("required") as? JsonObject
            ?: return emptyList()
        // Each required entry is `paramName: [TypeOrEnum, ...metadata]`.
        // Primitive types (INT/FLOAT/STRING/BOOLEAN) become widgets.
        // Connection types (MODEL, CLIP, LATENT, ...) are wired inputs
        // and appear under `inputs[]`, so we skip them here.
        return required.entries
            .filter { (_, value) -> isPrimitiveTypeSpec(value) }
            .map { it.key }
    }

    private fun isPrimitiveTypeSpec(spec: JsonElement): Boolean {
        val arr = spec as? JsonArray ?: return false
        if (arr.isEmpty()) return false
        val first = arr[0]
        // Two cases:
        //   ["INT", {...}] / ["FLOAT", {...}] / ["STRING", {...}] / ["BOOLEAN", {...}]
        //   [["a", "b", "c"], {...}]  — a combo (enum) widget
        return when (first) {
            is JsonPrimitive -> first.contentOrNull?.let { it in PRIMITIVE_TYPE_NAMES } == true
            is JsonArray -> true
            else -> false
        }
    }

    companion object {
        /**
         * Hard-coded widget declaration order for the v1 whitelist
         * classTypes. Sourced from ComfyUI master `nodes.py`
         * `INPUT_TYPES.required` definitions; matches what the desktop
         * webui produces in `widgets_values[]`.
         *
         * `KSampler` includes the auto-inserted
         * `control_after_generate` combo immediately after the seed
         * widget — it's UI-only and gets filtered out via
         * [UI_ONLY_WIDGETS] before writing to API inputs.
         */
        val WHITELIST_WIDGET_ORDER: Map<String, List<String>> = mapOf(
            "CheckpointLoaderSimple" to listOf("ckpt_name"),
            "CLIPTextEncode" to listOf("text"),
            "KSampler" to listOf(
                "seed",
                "control_after_generate", // UI-only auxiliary widget
                "steps",
                "cfg",
                "sampler_name",
                "scheduler",
                "denoise",
            ),
            "EmptyLatentImage" to listOf("width", "height", "batch_size"),
            "VAEDecode" to emptyList(),
            "SaveImage" to listOf("filename_prefix"),
            "LoraLoader" to listOf("lora_name", "strength_model", "strength_clip"),
            "ControlNetLoader" to listOf("control_net_name"),
            "ControlNetApply" to listOf("strength"),
            "ControlNetApplyAdvanced" to listOf("strength", "start_percent", "end_percent"),
        )

        /**
         * Widgets that appear in `widgets_values[]` but must be
         * stripped from the API `inputs` map because the server does
         * not accept them.
         */
        val UI_ONLY_WIDGETS: Set<String> = setOf(
            "control_after_generate",
            "prompt_after_generate",
        )

        private val PRIMITIVE_TYPE_NAMES = setOf(
            "INT", "FLOAT", "STRING", "BOOLEAN",
        )
    }
}
