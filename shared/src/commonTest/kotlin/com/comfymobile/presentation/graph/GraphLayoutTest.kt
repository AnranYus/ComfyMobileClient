package com.comfymobile.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure-data tests for [GraphLayout]. Per @Lily PR #19 thread
 * `4da46760` point 1 — layout is no-Compose / no-IO so a benchmark
 * fixture or this unit-test class can drive it directly.
 *
 * Coverage:
 *  - empty graph → empty result with [Rect.Zero] bounds
 *  - all-positions-recorded path: original `pos` honoured 1:1
 *  - any-position-missing path: BFS auto-layout left-to-right
 *  - port positions land on the node edges (output = right, input = left)
 *  - bezier control points stretch with horizontal distance
 *  - contentBounds is the tight bounding box
 *  - link to a missing node is dropped, not crashing
 */
class GraphLayoutTest {

    @Test fun empty_graph_yields_empty_result_with_zero_bounds() {
        val result = GraphLayout.layout(ParsedUiGraph(nodes = emptyList(), links = emptyList()))
        assertEquals(emptyMap<String, NodeRect>(), result.nodes)
        assertEquals(emptyMap<String, EdgePath>(), result.edges)
        assertEquals(Rect.Zero, result.contentBounds)
    }

    @Test fun all_nodes_with_pos_are_placed_at_their_recorded_positions() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(id = "1", pos = Position(100f, 50f), size = Size(200f, 150f)),
                node(id = "2", pos = Position(400f, 200f), size = Size(200f, 150f)),
            ),
            links = emptyList(),
        )
        val result = GraphLayout.layout(graph)
        assertEquals(NodeRect(100f, 50f, 200f, 150f), result.nodes["1"])
        assertEquals(NodeRect(400f, 200f, 200f, 150f), result.nodes["2"])
    }

    @Test fun bfs_auto_layout_assigns_columns_by_link_depth() {
        // 3 → 1 → 2: chain with 3 columns, no recorded positions.
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(id = "1", classType = "Mid"),
                node(id = "2", classType = "Sink"),
                node(id = "3", classType = "Source"),
            ),
            links = listOf(
                ParsedLink(linkId = "a", sourceNodeId = "3", sourceSlot = 0, targetNodeId = "1", targetSlot = 0, type = "MODEL"),
                ParsedLink(linkId = "b", sourceNodeId = "1", sourceSlot = 0, targetNodeId = "2", targetSlot = 0, type = "MODEL"),
            ),
        )
        val result = GraphLayout.layout(graph)
        // Source (column 0) at left, Mid (column 1) further right, Sink (column 2) furthest.
        val xSource = result.nodes["3"]!!.x
        val xMid = result.nodes["1"]!!.x
        val xSink = result.nodes["2"]!!.x
        assertTrue(xSource < xMid, "Source x ($xSource) should be left of Mid ($xMid)")
        assertTrue(xMid < xSink, "Mid x ($xMid) should be left of Sink ($xSink)")
    }

    @Test fun bfs_auto_layout_stacks_same_column_nodes_vertically() {
        // Two source nodes, both feeding the same sink. Sources share
        // column 0; sink at column 1. Verify the two source rects do
        // NOT overlap vertically.
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(id = "src-a", classType = "A"),
                node(id = "src-b", classType = "B"),
                node(id = "sink", classType = "C"),
            ),
            links = listOf(
                ParsedLink("a", "src-a", 0, "sink", 0, "MODEL"),
                ParsedLink("b", "src-b", 0, "sink", 1, "MODEL"),
            ),
        )
        val result = GraphLayout.layout(graph)
        val rectA = result.nodes["src-a"]!!
        val rectB = result.nodes["src-b"]!!
        assertEquals(rectA.x, rectB.x, "Same column should share x")
        // Either A above B or B above A — but not overlapping.
        assertTrue(
            rectA.bottom <= rectB.y || rectB.bottom <= rectA.y,
            "Same-column nodes should not overlap vertically; A=$rectA, B=$rectB",
        )
    }

    @Test fun bfs_auto_layout_breaks_cycles_without_infinite_loop() {
        // 1 → 2 → 1 cycle. We don't care about the exact layout
        // output, just that it terminates and produces rects for both
        // nodes.
        val graph = ParsedUiGraph(
            nodes = listOf(node("1"), node("2")),
            links = listOf(
                ParsedLink("a", "1", 0, "2", 0, "MODEL"),
                ParsedLink("b", "2", 0, "1", 0, "MODEL"),
            ),
        )
        val result = GraphLayout.layout(graph)
        assertNotNull(result.nodes["1"])
        assertNotNull(result.nodes["2"])
    }

    @Test fun output_port_lands_on_right_edge_input_port_on_left_edge() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(
                    id = "src",
                    pos = Position(0f, 0f),
                    size = Size(200f, 100f),
                    outputs = listOf(NodePort(0, "MODEL", "MODEL")),
                ),
                node(
                    id = "dst",
                    pos = Position(400f, 0f),
                    size = Size(200f, 100f),
                    inputs = listOf(NodePort(0, "model", "MODEL")),
                ),
            ),
            links = listOf(
                ParsedLink("e1", "src", 0, "dst", 0, "MODEL"),
            ),
        )
        val edge = GraphLayout.layout(graph).edges["e1"]!!
        // Source port on right edge of src node.
        assertEquals(200f, edge.start.x, "Output port x should be src.right (200)")
        // Target port on left edge of dst node.
        assertEquals(400f, edge.end.x, "Input port x should be dst.left (400)")
    }

    @Test fun bezier_control_points_stretch_with_horizontal_distance() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(id = "a", pos = Position(0f, 0f), size = Size(100f, 50f),
                    outputs = listOf(NodePort(0, "OUT", "MODEL"))),
                node(id = "b", pos = Position(500f, 0f), size = Size(100f, 50f),
                    inputs = listOf(NodePort(0, "IN", "MODEL"))),
            ),
            links = listOf(ParsedLink("e", "a", 0, "b", 0, "MODEL")),
        )
        val edge = GraphLayout.layout(graph).edges["e"]!!
        val handleStretch = (edge.end.x - edge.start.x) / 2f
        assertEquals(edge.start.x + handleStretch, edge.control1.x, 0.001f)
        assertEquals(edge.end.x - handleStretch, edge.control2.x, 0.001f)
    }

    @Test fun content_bounds_is_tight_bounding_box_over_all_nodes() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(id = "1", pos = Position(100f, 200f), size = Size(50f, 50f)),
                node(id = "2", pos = Position(400f, 50f), size = Size(60f, 80f)),
                node(id = "3", pos = Position(50f, 500f), size = Size(40f, 40f)),
            ),
            links = emptyList(),
        )
        val bounds = GraphLayout.layout(graph).contentBounds
        assertEquals(50f, bounds.left, "left = min(50, 100, 400) = 50")
        assertEquals(50f, bounds.top, "top = min(200, 50, 500) = 50")
        assertEquals(460f, bounds.right, "right = max(150, 460, 90) = 460")
        assertEquals(540f, bounds.bottom, "bottom = max(250, 130, 540) = 540")
    }

    @Test fun link_to_missing_node_is_dropped_no_crash() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(id = "1", pos = Position(0f, 0f),
                    outputs = listOf(NodePort(0, "OUT", "MODEL"))),
            ),
            links = listOf(
                ParsedLink("dangling", "1", 0, "ghost", 0, "MODEL"),
            ),
        )
        val result = GraphLayout.layout(graph)
        assertEquals(emptyMap<String, EdgePath>(), result.edges)
    }

    // ---------------------------------------------------------------- helpers

    private fun node(
        id: String,
        classType: String = "Stub",
        pos: Position? = null,
        size: Size? = null,
        inputs: List<NodePort> = emptyList(),
        outputs: List<NodePort> = emptyList(),
    ): ParsedNode = ParsedNode(
        id = id,
        classType = classType,
        title = null,
        originalPos = pos,
        originalSize = size,
        inputs = inputs,
        outputs = outputs,
    )
}
