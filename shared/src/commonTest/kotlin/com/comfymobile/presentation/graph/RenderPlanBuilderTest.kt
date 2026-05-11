package com.comfymobile.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the render-plan command-list contract so per-node assertions
 * (snapshot tests, perf "visible draw commands ≤ N", animation
 * synchronisation) can rely on a stable shape.
 *
 * Per @Lily PR #19 thread `4da46760` point 2: Compose Canvas must NOT
 * look up descriptor / status / runtime state during `drawScope`.
 * RenderPlan is the data list it consumes in order — these tests
 * pin the data shape so that contract holds.
 */
class RenderPlanBuilderTest {

    private val palette = GraphPalette.defaultLightForTesting

    private fun port(slot: Int, type: String): NodePort = NodePort(slot, "p$slot", type)

    private fun node(
        id: String,
        cls: String = "Stub",
        pos: Position = Position(0f, 0f),
        size: Size = Size(200f, 100f),
        outputs: List<NodePort> = emptyList(),
        inputs: List<NodePort> = emptyList(),
    ): ParsedNode = ParsedNode(
        id = id,
        classType = cls,
        title = null,
        originalPos = pos,
        originalSize = size,
        inputs = inputs,
        outputs = outputs,
    )

    private fun buildPlan(
        graph: ParsedUiGraph,
        bodyMode: BodyMode = BodyMode.FULL,
        visibleBounds: Rect? = null,
        runtimeStatus: NodeRuntimeStatus = NodeRuntimeStatus.IDLE,
        resolveSummaryRows: (ParsedNode) -> List<SummaryEntry> = { emptyList() },
    ): RenderPlan {
        val layout = GraphLayout.layout(graph)
        return RenderPlanBuilder.build(
            graph = graph,
            layoutResult = layout,
            resolveStyle = { _ ->
                NodeStyle(
                    fillArgb = palette.nodeFillArgb,
                    titleArgb = palette.titleArgb,
                    borderArgb = palette.borderArgb,
                    borderWidthDp = 1f,
                    statusBadge = when (runtimeStatus) {
                        NodeRuntimeStatus.IDLE -> StatusBadge.NONE
                        NodeRuntimeStatus.RUNNING -> StatusBadge.SPINNER
                        NodeRuntimeStatus.DONE -> StatusBadge.DONE
                        NodeRuntimeStatus.CACHED -> StatusBadge.CACHED
                        NodeRuntimeStatus.ERROR -> StatusBadge.ERROR
                    },
                    bodyMode = bodyMode,
                    showSelected = false,
                )
            },
            resolvePortStyle = { p -> NodeStyleResolver.resolvePort(p, palette) },
            resolveTitle = { n -> NodeTitleSpec(n.classType, italic = bodyMode == BodyMode.TITLE_ONLY) },
            resolveSummaryRows = resolveSummaryRows,
            visibleBounds = visibleBounds,
        )
    }

    // ---------------------------------------------------------------- ordering / count invariants

    @Test fun edges_are_emitted_before_nodes_so_bodies_layer_on_top() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node("a", outputs = listOf(port(0, "MODEL"))),
                node("b", pos = Position(400f, 0f), inputs = listOf(port(0, "MODEL"))),
            ),
            links = listOf(ParsedLink("e", "a", 0, "b", 0, "MODEL")),
        )
        val plan = buildPlan(graph)
        val firstNodeIdx = plan.commands.indexOfFirst { it is DrawCommand.NodeBody }
        val firstEdgeIdx = plan.commands.indexOfFirst { it is DrawCommand.Edge }
        assertTrue(firstEdgeIdx >= 0 && firstNodeIdx >= 0, "Need both edge + node in plan")
        assertTrue(firstEdgeIdx < firstNodeIdx, "Edges must be emitted before nodes (bodies cover edges near ports)")
    }

    @Test fun visible_node_count_equals_input_node_count_when_no_viewport_clipping() {
        val graph = ParsedUiGraph(
            nodes = (1..5).map { node(it.toString(), pos = Position(it * 250f, 0f)) },
            links = emptyList(),
        )
        val plan = buildPlan(graph)
        assertEquals(5, plan.visibleNodeCount())
    }

    @Test fun visible_edge_count_equals_input_link_count_in_full_view() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node("a", outputs = listOf(port(0, "MODEL"))),
                node("b", pos = Position(400f, 0f), inputs = listOf(port(0, "MODEL"))),
                node("c", pos = Position(800f, 0f), inputs = listOf(port(0, "MODEL"))),
            ),
            links = listOf(
                ParsedLink("e1", "a", 0, "b", 0, "MODEL"),
                ParsedLink("e2", "a", 0, "c", 0, "MODEL"),
            ),
        )
        val plan = buildPlan(graph)
        assertEquals(2, plan.visibleEdgeCount())
    }

    // ---------------------------------------------------------------- viewport virtualisation

    @Test fun viewport_clipping_skips_nodes_entirely_outside_visible_bounds() {
        // Three nodes laid out across x=0, x=1000, x=2000. Viewport
        // covers only x=0..500 → only the first node should appear.
        val graph = ParsedUiGraph(
            nodes = listOf(
                node("a", pos = Position(0f, 0f), size = Size(200f, 100f)),
                node("b", pos = Position(1000f, 0f), size = Size(200f, 100f)),
                node("c", pos = Position(2000f, 0f), size = Size(200f, 100f)),
            ),
            links = emptyList(),
        )
        val plan = buildPlan(
            graph,
            visibleBounds = Rect(left = 0f, top = 0f, right = 500f, bottom = 200f),
        )
        val nodeIds = plan.commands.filterIsInstance<DrawCommand.NodeBody>().map { it.nodeId }.toSet()
        assertEquals(setOf("a"), nodeIds)
    }

    @Test fun viewport_keeps_edges_with_at_least_one_endpoint_inside() {
        // Source inside viewport, target outside. Edge should still
        // render so the user sees the connection terminate.
        val graph = ParsedUiGraph(
            nodes = listOf(
                node("a", pos = Position(0f, 0f), size = Size(200f, 100f),
                    outputs = listOf(port(0, "MODEL"))),
                node("b", pos = Position(1000f, 0f), size = Size(200f, 100f),
                    inputs = listOf(port(0, "MODEL"))),
            ),
            links = listOf(ParsedLink("e", "a", 0, "b", 0, "MODEL")),
        )
        val plan = buildPlan(
            graph,
            visibleBounds = Rect(left = 0f, top = 0f, right = 500f, bottom = 200f),
        )
        assertEquals(1, plan.visibleEdgeCount())
    }

    @Test fun viewport_drops_edges_with_both_endpoints_outside_bounds() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node("a", pos = Position(1000f, 0f), size = Size(200f, 100f),
                    outputs = listOf(port(0, "MODEL"))),
                node("b", pos = Position(2000f, 0f), size = Size(200f, 100f),
                    inputs = listOf(port(0, "MODEL"))),
            ),
            links = listOf(ParsedLink("e", "a", 0, "b", 0, "MODEL")),
        )
        val plan = buildPlan(
            graph,
            visibleBounds = Rect(left = 0f, top = 0f, right = 500f, bottom = 200f),
        )
        assertEquals(0, plan.visibleEdgeCount())
    }

    // ---------------------------------------------------------------- per-node sub-commands

    @Test fun full_body_node_emits_body_title_and_one_port_command_per_port() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(
                    id = "n",
                    inputs = listOf(port(0, "MODEL"), port(1, "CLIP")),
                    outputs = listOf(port(0, "VAE")),
                ),
            ),
            links = emptyList(),
        )
        val plan = buildPlan(graph, bodyMode = BodyMode.FULL)
        val commandsForN = plan.commands.filter { it.nodeIdOrNull() == "n" }
        // 1 body + 1 title + 2 input ports + 1 output port = 5
        assertEquals(5, commandsForN.size)
        assertEquals(1, commandsForN.filterIsInstance<DrawCommand.NodeBody>().size)
        assertEquals(1, commandsForN.filterIsInstance<DrawCommand.NodeTitle>().size)
        assertEquals(3, commandsForN.filterIsInstance<DrawCommand.NodePort>().size)
    }

    @Test fun title_only_unknown_node_emits_no_port_commands() {
        val graph = ParsedUiGraph(
            nodes = listOf(
                node(
                    id = "unknown",
                    inputs = listOf(port(0, "ANY")),
                    outputs = listOf(port(0, "ANY")),
                ),
            ),
            links = emptyList(),
        )
        val plan = buildPlan(graph, bodyMode = BodyMode.TITLE_ONLY)
        val ports = plan.commands.filterIsInstance<DrawCommand.NodePort>()
        assertEquals(0, ports.size)
        // Body + title still draw.
        assertEquals(1, plan.commands.filterIsInstance<DrawCommand.NodeBody>().size)
        assertEquals(1, plan.commands.filterIsInstance<DrawCommand.NodeTitle>().size)
    }

    @Test fun edge_argb_matches_source_port_type_colour() {
        // Source port type = "CLIP" → blue (0xFF1E88E5).
        val graph = ParsedUiGraph(
            nodes = listOf(
                node("a", outputs = listOf(port(0, "CLIP"))),
                node("b", pos = Position(400f, 0f), inputs = listOf(port(0, "CLIP"))),
            ),
            links = listOf(ParsedLink("e", "a", 0, "b", 0, "CLIP")),
        )
        val plan = buildPlan(graph)
        val edge = plan.commands.filterIsInstance<DrawCommand.Edge>().single()
        assertEquals(0xFF1E88E5, edge.argb)
    }

    @Test fun running_status_propagates_to_title_command_status_badge() {
        val graph = ParsedUiGraph(
            nodes = listOf(node("n")),
            links = emptyList(),
        )
        val plan = buildPlan(graph, runtimeStatus = NodeRuntimeStatus.RUNNING)
        val title = plan.commands.filterIsInstance<DrawCommand.NodeTitle>().single()
        assertEquals(StatusBadge.SPINNER, title.statusBadge)
    }

    // ---------------------------------------------------------------- §1.3 summary rows

    @Test fun summary_rows_emit_one_drawcommand_per_resolved_entry() {
        // Per @Ores T2.7 §1.3: each SummaryEntry produces one
        // DrawCommand.SummaryRow with monotonically increasing
        // rowIndex starting from 0.
        val graph = ParsedUiGraph(
            nodes = listOf(node("n")),
            links = emptyList(),
        )
        val plan = buildPlan(
            graph,
            bodyMode = BodyMode.FULL,
            resolveSummaryRows = { _ ->
                listOf(
                    SummaryEntry(text = "seed: 12345", emphasis = SummaryEntry.Emphasis.PARAM),
                    SummaryEntry(text = "steps: 30", emphasis = SummaryEntry.Emphasis.PARAM),
                    SummaryEntry(text = "cfg: 7.5", emphasis = SummaryEntry.Emphasis.PARAM),
                    SummaryEntry(text = "…3 more", emphasis = SummaryEntry.Emphasis.MORE_HINT),
                )
            },
        )
        val rows = plan.commands.filterIsInstance<DrawCommand.SummaryRow>()
        assertEquals(4, rows.size)
        assertEquals(4, plan.visibleSummaryRowCount())
        // rowIndex monotonic
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.rowIndex })
        // text preserved
        assertEquals("seed: 12345", rows[0].text)
        assertEquals("…3 more", rows[3].text)
        // emphasis preserved
        assertEquals(SummaryEntry.Emphasis.PARAM, rows[0].emphasis)
        assertEquals(SummaryEntry.Emphasis.MORE_HINT, rows[3].emphasis)
        // colours route by emphasis: PARAM rows get paramTextArgb,
        // MORE_HINT gets moreHintTextArgb. Defaults from
        // SummaryRowPalette.defaultLightForTesting.
        val defaultPalette = SummaryRowPalette.defaultLightForTesting
        assertEquals(defaultPalette.paramTextArgb, rows[0].textArgb)
        assertEquals(defaultPalette.moreHintTextArgb, rows[3].textArgb)
    }

    @Test fun summary_rows_origin_advances_vertically_with_constant_pitch() {
        val graph = ParsedUiGraph(
            nodes = listOf(node("n", pos = Position(100f, 50f))),
            links = emptyList(),
        )
        val plan = buildPlan(
            graph,
            resolveSummaryRows = { _ ->
                listOf(
                    SummaryEntry("a: 1"),
                    SummaryEntry("b: 2"),
                    SummaryEntry("c: 3"),
                )
            },
        )
        val rows = plan.commands.filterIsInstance<DrawCommand.SummaryRow>()
        // All rows share the same x (left padding inside node body)
        val xs = rows.map { it.origin.x }.toSet()
        assertEquals(1, xs.size, "all rows should align on x: $xs")
        // Row y deltas are constant (rowPitch)
        val ys = rows.map { it.origin.y }
        val deltas = ys.zipWithNext { a, b -> b - a }
        assertEquals(1, deltas.toSet().size, "row pitch should be constant, got deltas: $deltas")
        assertTrue(deltas.single() > 0f, "rows should advance downward")
    }

    @Test fun title_only_node_does_NOT_emit_summary_rows() {
        // Per @Ores §1.1 + §1.3: unknown nodes collapse to title-only
        // and never show summary content.
        val graph = ParsedUiGraph(
            nodes = listOf(node("u")),
            links = emptyList(),
        )
        val plan = buildPlan(
            graph,
            bodyMode = BodyMode.TITLE_ONLY,
            // Even if a resolver tries to inject rows for an unknown node,
            // the builder must skip them — TITLE_ONLY → no body content.
            resolveSummaryRows = { _ -> listOf(SummaryEntry("should not appear")) },
        )
        assertEquals(0, plan.visibleSummaryRowCount())
    }

    private fun DrawCommand.nodeIdOrNull(): String? = when (this) {
        is DrawCommand.NodeBody -> nodeId
        is DrawCommand.NodeTitle -> nodeId
        is DrawCommand.NodePort -> nodeId
        is DrawCommand.SummaryRow -> nodeId
        is DrawCommand.Edge -> null
    }
}
