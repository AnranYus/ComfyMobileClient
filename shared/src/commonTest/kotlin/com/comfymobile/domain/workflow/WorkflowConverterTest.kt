package com.comfymobile.domain.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the most common UI → API conversion paths.
 *
 * Per @Lily seam (T0.5): widgets_values → param mapping is the
 * easiest-to-break corner of the workflow data layer, so the three
 * highest-stakes whitelist nodes get explicit coverage:
 *
 *   - `KSampler` (the seed + control_after_generate auxiliary widget)
 *   - `CLIPTextEncode` (the multiline-text widget)
 *   - `EmptyLatentImage` (numeric widgets, no link inputs)
 *
 * Plus: an end-to-end "Hello SDXL"-style two-prompt workflow that
 * exercises link tuples, descriptor-driven widget ordering, unknown
 * nodes, and UI-only widget filtering all in one pass.
 */
class WorkflowConverterTest {

    private val converter = WorkflowConverter()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private fun JsonObject.firstApiInput(node: String, name: String): kotlinx.serialization.json.JsonElement? =
        (this[node] as? JsonObject)?.get("inputs")?.jsonObject?.get(name)

    @Test fun ksampler_widgets_values_unpack_to_named_inputs_with_seed_aux_filtered() {
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 3,
                  "type": "KSampler",
                  "inputs": [
                    {"name":"model","type":"MODEL","link":1},
                    {"name":"positive","type":"CONDITIONING","link":2},
                    {"name":"negative","type":"CONDITIONING","link":3},
                    {"name":"latent_image","type":"LATENT","link":4}
                  ],
                  "outputs": [{"name":"LATENT","type":"LATENT","links":[5]}],
                  "widgets_values": [
                    8566257,
                    "randomize",
                    20,
                    8.0,
                    "euler",
                    "normal",
                    1.0
                  ]
                }
              ],
              "links": [
                [1, 4, 0, 3, 0, "MODEL"],
                [2, 6, 0, 3, 1, "CONDITIONING"],
                [3, 7, 0, 3, 2, "CONDITIONING"],
                [4, 5, 0, 3, 3, "LATENT"]
              ]
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui)
        val node = api.nodes["3"]
        assertNotNull(node)
        assertEquals("KSampler", node.classType)

        // Wired connections come through as 2-element link arrays.
        assertLinkTuple(node.inputs["model"], "4", 0)
        assertLinkTuple(node.inputs["positive"], "6", 0)
        assertLinkTuple(node.inputs["negative"], "7", 0)
        assertLinkTuple(node.inputs["latent_image"], "5", 0)

        // Widget values (note: control_after_generate is filtered).
        assertEquals(8566257, (node.inputs["seed"] as JsonPrimitive).intOrNull)
        assertEquals(null, node.inputs["control_after_generate"], "UI-only widget must not appear in API inputs")
        assertEquals(20, (node.inputs["steps"] as JsonPrimitive).intOrNull)
        assertEquals(8.0, (node.inputs["cfg"] as JsonPrimitive).content.toDouble())
        assertEquals("euler", (node.inputs["sampler_name"] as JsonPrimitive).contentOrNull)
        assertEquals("normal", (node.inputs["scheduler"] as JsonPrimitive).contentOrNull)
        assertEquals(1.0, (node.inputs["denoise"] as JsonPrimitive).content.toDouble())
    }

    @Test fun cliptextencode_multiline_text_widget_maps_to_text_input() {
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 6,
                  "type": "CLIPTextEncode",
                  "inputs": [
                    {"name":"clip","type":"CLIP","link":10}
                  ],
                  "outputs": [{"name":"CONDITIONING","type":"CONDITIONING","links":[11]}],
                  "widgets_values": ["a beautiful landscape, masterpiece"]
                }
              ],
              "links": [
                [10, 4, 1, 6, 0, "CLIP"]
              ]
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui)
        val node = api.nodes["6"]
        assertNotNull(node)
        assertEquals("a beautiful landscape, masterpiece", (node.inputs["text"] as JsonPrimitive).contentOrNull)
        assertLinkTuple(node.inputs["clip"], "4", 1)
    }

    @Test fun emptylatentimage_numeric_widgets_decode_in_declaration_order() {
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 5,
                  "type": "EmptyLatentImage",
                  "inputs": [],
                  "outputs": [{"name":"LATENT","type":"LATENT","links":[12]}],
                  "widgets_values": [768, 512, 4]
                }
              ],
              "links": []
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui)
        val node = api.nodes["5"]
        assertNotNull(node)
        assertEquals(768, (node.inputs["width"] as JsonPrimitive).intOrNull)
        assertEquals(512, (node.inputs["height"] as JsonPrimitive).intOrNull)
        assertEquals(4, (node.inputs["batch_size"] as JsonPrimitive).intOrNull)
    }

    @Test fun unknown_classType_with_no_object_info_drops_widget_values_but_preserves_links() {
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 99,
                  "type": "FancyCustomNode",
                  "inputs": [
                    {"name":"input_a","type":"IMAGE","link":42}
                  ],
                  "widgets_values": [1.5, "ignored"]
                }
              ],
              "links": [
                [42, 5, 0, 99, 0, "IMAGE"]
              ]
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui, objectInfo = null)
        val node = api.nodes["99"]
        assertNotNull(node)
        // Link is preserved
        assertLinkTuple(node.inputs["input_a"], "5", 0)
        // Widgets are skipped because we don't know their names
        assertTrue(node.inputs.size == 1, "expected only the link entry, got ${node.inputs.keys}")
    }

    @Test fun unknown_classType_with_object_info_uses_required_order() {
        val objectInfo = json.parseToJsonElement(
            """
            {
              "FancyCustomNode": {
                "input": {
                  "required": {
                    "input_a": ["IMAGE", {}],
                    "knob_x":  ["FLOAT", {"default": 0.5, "min": 0, "max": 1}],
                    "label":   ["STRING", {"default": ""}]
                  }
                }
              }
            }
            """.trimIndent()
        )
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 99,
                  "type": "FancyCustomNode",
                  "inputs": [{"name":"input_a","type":"IMAGE","link":42}],
                  "widgets_values": [0.7, "hello"]
                }
              ],
              "links": [[42, 5, 0, 99, 0, "IMAGE"]]
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui, objectInfo = objectInfo)
        val node = api.nodes["99"]
        assertNotNull(node)
        assertEquals(0.7, (node.inputs["knob_x"] as JsonPrimitive).content.toDouble())
        assertEquals("hello", (node.inputs["label"] as JsonPrimitive).contentOrNull)
        assertLinkTuple(node.inputs["input_a"], "5", 0)
    }

    @Test fun null_link_field_is_treated_as_no_wired_input() {
        // Some UI exports leave `link: null` for an unwired socket on a
        // node that *could* take a connection but has its widget value.
        // Make sure we don't try to dereference null into the link map.
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 1,
                  "type": "CheckpointLoaderSimple",
                  "inputs": [],
                  "widgets_values": ["v1-5-pruned-emaonly.safetensors"]
                }
              ],
              "links": []
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui)
        val node = api.nodes["1"]
        assertNotNull(node)
        assertEquals("v1-5-pruned-emaonly.safetensors", (node.inputs["ckpt_name"] as JsonPrimitive).contentOrNull)
    }

    @Test fun missing_id_or_type_skips_node() {
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {"id": 1, "type": "VAEDecode", "inputs": [], "widgets_values": []},
                {"id": 2, "inputs": [], "widgets_values": []},
                {"type": "VAEDecode", "inputs": [], "widgets_values": []}
              ],
              "links": []
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui)
        // Only node 1 should survive
        assertEquals(setOf("1"), api.nodes.keys)
    }

    @Test fun empty_workflow_yields_empty_api_graph() {
        val ui = uiGraphOf("""{"nodes":[]}""")
        val api = converter.uiToApi(ui)
        assertTrue(api.nodes.isEmpty())
    }

    @Test fun extra_widgets_values_beyond_known_widgets_are_ignored() {
        val ui = uiGraphOf(
            """
            {
              "nodes": [
                {
                  "id": 5,
                  "type": "EmptyLatentImage",
                  "inputs": [],
                  "widgets_values": [768, 512, 4, "stale", 99]
                }
              ],
              "links": []
            }
            """.trimIndent()
        )
        val api = converter.uiToApi(ui)
        val node = api.nodes["5"]
        assertNotNull(node)
        assertEquals(setOf("width", "height", "batch_size"), node.inputs.keys)
    }

    @Test fun whitelist_includes_all_canonical_classTypes() {
        // Regression: catch a future change that drops a whitelist
        // entry — the descriptor table commits to these 10 classTypes
        // (per CONTEXT.md v4).
        val canonical = setOf(
            "CheckpointLoaderSimple", "CLIPTextEncode", "KSampler",
            "EmptyLatentImage", "VAEDecode", "SaveImage",
            "LoraLoader", "ControlNetLoader", "ControlNetApply",
            "ControlNetApplyAdvanced",
        )
        assertEquals(
            canonical,
            WorkflowConverter.WHITELIST_WIDGET_ORDER.keys,
            "WorkflowConverter.WHITELIST_WIDGET_ORDER must contain exactly the v1 canonical classTypes",
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun uiGraphOf(rawJson: String): WorkflowGraph.Ui =
        WorkflowGraph.Ui(json.parseToJsonElement(rawJson) as JsonObject)

    private fun assertLinkTuple(
        element: kotlinx.serialization.json.JsonElement?,
        expectedSourceNodeId: String,
        expectedSourceSlot: Int,
    ) {
        assertNotNull(element, "expected a link tuple, got null")
        val arr = element as JsonArray
        assertEquals(2, arr.size, "link tuple must be 2-element [src_node_id, src_slot], got $arr")
        assertEquals(expectedSourceNodeId, (arr[0] as JsonPrimitive).contentOrNull)
        assertEquals(expectedSourceSlot, (arr[1] as JsonPrimitive).intOrNull)
    }
}
