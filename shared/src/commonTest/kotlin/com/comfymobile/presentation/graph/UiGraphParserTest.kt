package com.comfymobile.presentation.graph

import com.comfymobile.domain.workflow.WorkflowGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage matrix:
 *  - canonical editor save (id as int, pos as array, size as object)
 *  - id as string (older saves / API-mode round-trips)
 *  - pos as object `{"0": ..., "1": ...}` (some custom-node forks)
 *  - missing pos / size → null in [ParsedNode]
 *  - malformed link tuple → dropped, others survive
 *  - empty workflow → empty result, not crash
 */
class UiGraphParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(jsonString: String): ParsedUiGraph =
        UiGraphParser.parse(WorkflowGraph.Ui(json.parseToJsonElement(jsonString) as JsonObject))

    @Test fun parses_canonical_editor_save_with_two_nodes_and_one_link() {
        val workflow = """
            {
              "last_node_id": 2,
              "last_link_id": 1,
              "nodes": [
                {
                  "id": 1,
                  "type": "CheckpointLoaderSimple",
                  "title": "checkpoint",
                  "pos": [100, 200],
                  "size": {"0": 320, "1": 100},
                  "inputs": [],
                  "outputs": [
                    { "name": "MODEL", "type": "MODEL", "links": [1] }
                  ]
                },
                {
                  "id": 2,
                  "type": "KSampler",
                  "pos": [600, 200],
                  "size": {"0": 320, "1": 320},
                  "inputs": [
                    { "name": "model", "type": "MODEL", "link": 1 }
                  ],
                  "outputs": []
                }
              ],
              "links": [ [1, 1, 0, 2, 0, "MODEL"] ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        assertEquals(2, parsed.nodes.size)
        val checkpoint = parsed.nodes.first { it.classType == "CheckpointLoaderSimple" }
        assertEquals("1", checkpoint.id)
        assertEquals("checkpoint", checkpoint.title)
        assertEquals(Position(100f, 200f), checkpoint.originalPos)
        assertEquals(Size(320f, 100f), checkpoint.originalSize)
        assertEquals(0, checkpoint.inputs.size)
        assertEquals(1, checkpoint.outputs.size)
        assertEquals(NodePort(slotIndex = 0, name = "MODEL", type = "MODEL"), checkpoint.outputs[0])

        assertEquals(1, parsed.links.size)
        val link = parsed.links.single()
        assertEquals("1", link.linkId)
        assertEquals("1", link.sourceNodeId)
        assertEquals(0, link.sourceSlot)
        assertEquals("2", link.targetNodeId)
        assertEquals(0, link.targetSlot)
        assertEquals("MODEL", link.type)
    }

    @Test fun accepts_pos_as_object_and_size_as_array() {
        val workflow = """
            {
              "nodes": [
                {
                  "id": 5,
                  "type": "VAEDecode",
                  "pos": {"0": 50.5, "1": 75.25},
                  "size": [200, 80]
                }
              ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        val node = parsed.nodes.single()
        assertEquals(Position(50.5f, 75.25f), node.originalPos)
        assertEquals(Size(200f, 80f), node.originalSize)
    }

    @Test fun accepts_string_ids_and_normalizes_them_to_string() {
        val workflow = """
            {
              "nodes": [
                { "id": "42", "type": "LoadImage" },
                { "id": 7,    "type": "KSampler" }
              ],
              "links": [ ["1", "42", 0, "7", 0, "IMAGE"] ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        assertEquals(setOf("42", "7"), parsed.nodes.map { it.id }.toSet())
        val link = parsed.links.single()
        assertEquals("42", link.sourceNodeId)
        assertEquals("7", link.targetNodeId)
    }

    @Test fun missing_pos_and_size_round_trip_to_null() {
        val workflow = """
            {
              "nodes": [ { "id": 1, "type": "EmptyLatentImage" } ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        val node = parsed.nodes.single()
        assertNull(node.originalPos)
        assertNull(node.originalSize)
        assertEquals(emptyList<NodePort>(), node.inputs)
        assertEquals(emptyList<NodePort>(), node.outputs)
    }

    @Test fun malformed_link_tuple_is_dropped_others_survive() {
        val workflow = """
            {
              "nodes": [],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [],
                ["bad"],
                [3, 1, 0, 2, 0, "VAE"]
              ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        assertEquals(2, parsed.links.size)
        assertEquals(setOf("1", "3"), parsed.links.map { it.linkId }.toSet())
    }

    @Test fun empty_workflow_object_returns_empty_parsed_graph() {
        val parsed = parse("{}")
        assertEquals(emptyList<ParsedNode>(), parsed.nodes)
        assertEquals(emptyList<ParsedLink>(), parsed.links)
    }

    @Test fun port_type_defaults_to_UNKNOWN_when_missing() {
        val workflow = """
            {
              "nodes": [
                {
                  "id": 1,
                  "type": "CustomNode",
                  "outputs": [ { "name": "weird" } ]
                }
              ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        val port = parsed.nodes.single().outputs.single()
        assertEquals("UNKNOWN", port.type)
    }

    @Test fun node_without_id_or_type_is_dropped() {
        val workflow = """
            {
              "nodes": [
                { "type": "CheckpointLoaderSimple" },
                { "id": 1 },
                { "id": 2, "type": "KSampler" }
              ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        assertEquals(1, parsed.nodes.size)
        assertEquals("2", parsed.nodes.single().id)
    }

    @Test fun port_slot_index_matches_array_position_not_a_field() {
        // Per ComfyUI editor save: ports are an ordered array; the
        // slot index is the array position. We do NOT honour a `slot`
        // field on the port object — that field doesn't exist in the
        // canonical save and adding parser tolerance for it could
        // mask actual bugs.
        val workflow = """
            {
              "nodes": [
                {
                  "id": 1,
                  "type": "DualNode",
                  "outputs": [
                    { "name": "a", "type": "MODEL" },
                    { "name": "b", "type": "CLIP" },
                    { "name": "c", "type": "VAE" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsed = parse(workflow)
        val outputs = parsed.nodes.single().outputs
        assertEquals(0, outputs[0].slotIndex)
        assertEquals(1, outputs[1].slotIndex)
        assertEquals(2, outputs[2].slotIndex)
    }
}
