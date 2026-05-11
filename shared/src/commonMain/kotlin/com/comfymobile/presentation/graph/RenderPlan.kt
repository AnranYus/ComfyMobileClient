package com.comfymobile.presentation.graph

/**
 * Flat list of draw commands ready for the Compose Canvas
 * `drawScope` to consume in order.
 *
 * Per @Lily PR #19 thread `4da46760` point 2: the render plan is a
 * data list — Canvas does NOT look up descriptor / status / runtime
 * state during `drawScope`. That keeps `drawScope` deterministic
 * (snapshots / golden tests assert against the command list) and
 * makes "visible draw commands ≤ N" perf assertions possible without
 * a real GPU.
 *
 * Pure data — no Compose `Color` / `Path` / `Offset`. The Compose
 * adapter walks the list once per recomposition and translates each
 * [DrawCommand] into the matching `drawScope` call.
 */
data class RenderPlan(val commands: List<DrawCommand>) {

    /** Convenience: count nodes in the plan (for perf assertions). */
    fun visibleNodeCount(): Int = commands.count { it is DrawCommand.NodeBody }

    /** Convenience: count edges in the plan. */
    fun visibleEdgeCount(): Int = commands.count { it is DrawCommand.Edge }

    /** Convenience: count summary rows in the plan (for perf + UX assertions). */
    fun visibleSummaryRowCount(): Int = commands.count { it is DrawCommand.SummaryRow }
}

/**
 * Palette for the per-node summary-row text. Split from
 * [GraphPalette] so callers can theme summary text independently of
 * the node body colours (per @Ores T2.7 §1.3 — body text uses a
 * muted role that may differ from the node-body fill).
 */
data class SummaryRowPalette(
    val paramTextArgb: Long,
    val moreHintTextArgb: Long,
    val fontSizeSp: Float = 11f,
) {
    companion object {
        /** Light-theme defaults used by tests and previews. */
        val defaultLightForTesting: SummaryRowPalette = SummaryRowPalette(
            paramTextArgb = 0xFF424242,    // onSurface medium
            moreHintTextArgb = 0xFF9E9E9E, // onSurface low / outline
            fontSizeSp = 11f,
        )
    }
}

/**
 * One drawing operation in [RenderPlan]. Sealed so the Compose
 * adapter's `when` is exhaustive.
 *
 * Order matters: edges draw beneath nodes (so connection lines tuck
 * under node rects), so [Edge] commands appear before [NodeBody]
 * commands in the list.
 */
sealed interface DrawCommand {

    /**
     * Connection between two ports. Drawn first so node bodies
     * overlap the edge near each port.
     *
     * @property linkId Stable id from the parsed graph; useful when
     *   tests assert "this specific edge appears in the plan".
     */
    data class Edge(
        val linkId: String,
        val path: EdgePath,
        val argb: Long,
        val widthDp: Float,
        /**
         * If true, the renderer should fall back to a **straight
         * line** from start→end instead of the smooth bezier (per
         * @Ores PR #21 §1.5 / §1.8 locked LOD contract msg `26c8f2f3`:
         * "any active pan/zoom/node-drag → Bezier → straight line;
         * release → Bezier"). T2.1a always emits `false`; T2.1b's
         * gesture handler toggles to `true` during interaction.
         */
        val straightLineFallback: Boolean = false,
    ) : DrawCommand

    /**
     * Node body rectangle (fill + border). One per visible node.
     */
    data class NodeBody(
        val nodeId: String,
        val rect: NodeRect,
        val fillArgb: Long,
        val borderArgb: Long,
        val borderWidthDp: Float,
        val cornerRadiusDp: Float,
    ) : DrawCommand

    /**
     * Title bar inside a node — distinct fill + the (descriptor
     * displayName | classType) text on top.
     */
    data class NodeTitle(
        val nodeId: String,
        val rect: NodeRect,
        val titleArgb: Long,
        val text: String,
        val italic: Boolean,
        val statusBadge: StatusBadge,
        /**
         * If non-null, draw the badge with this colour (per
         * [NodeStyleResolver.statusAccentArgb]).
         */
        val statusAccentArgb: Long?,
    ) : DrawCommand

    /**
     * One port circle on a node edge. Output ports are drawn on the
     * right edge, input ports on the left.
     */
    data class NodePort(
        val nodeId: String,
        val portIndex: Int,
        val center: Position,
        val radiusDp: Float,
        val argb: Long,
        val isOutput: Boolean,
    ) : DrawCommand

    /**
     * One pre-formatted summary line drawn inside a FULL-body node
     * below the title bar. Per @Ores T2.7 §1.3 + clarification msg
     * `6b943636`: shows `descriptor.editableParams.take(3)` in
     * declaration order, with a trailing `…N more` line when
     * `editableParams.size > 4`.
     *
     * Pre-formatted by [SummaryRowResolver] so the Compose drawScope
     * never has to look up descriptors / control types.
     */
    data class SummaryRow(
        val nodeId: String,
        val rowIndex: Int,
        val origin: Position,
        val text: String,
        val emphasis: SummaryEntry.Emphasis,
        val textArgb: Long,
        val fontSizeSp: Float,
    ) : DrawCommand
}

object RenderPlanBuilder {

    /**
     * Build a [RenderPlan] from layout + style + visible viewport.
     *
     * @param layoutResult [GraphLayout.layout] output.
     * @param resolveStyle Per-node style resolver. Tests pass a
     *   pre-baked Map<String, NodeStyle>; production resolves on the
     *   fly via [NodeStyleResolver].
     * @param resolvePortStyle Per-port colour resolver. Same pattern.
     * @param resolveTitle Resolves a node's display text — descriptor
     *   `displayName` when known, otherwise `classType` verbatim.
     * @param visibleBounds Optional viewport rect. Nodes whose
     *   [NodeRect] does not intersect [visibleBounds] are skipped
     *   entirely (no [DrawCommand.NodeBody], no children) so
     *   T2.1b can drop off-screen work cheaply. Pass `null` to
     *   render everything (default — T2.1a doesn't have a viewport
     *   yet).
     * @param graph [ParsedUiGraph] — needed to look up port
     *   descriptors per node + link source/target.
     */
    fun build(
        graph: ParsedUiGraph,
        layoutResult: LayoutResult,
        resolveStyle: (ParsedNode) -> NodeStyle,
        resolvePortStyle: (com.comfymobile.presentation.graph.NodePort) -> PortStyle,
        resolveTitle: (ParsedNode) -> NodeTitleSpec,
        /**
         * Resolves a node's summary rows for §1.3 in-card param
         * preview. Returns empty list when the node is unknown
         * (TITLE_ONLY body) or the descriptor has no editableParams.
         *
         * Production callers wire this to:
         * ```
         * { node -> SummaryRowResolver.resolve(node, registry.lookup(node.classType)) }
         * ```
         */
        resolveSummaryRows: (ParsedNode) -> List<SummaryEntry> = { emptyList() },
        visibleBounds: Rect? = null,
        config: LayoutConfig = LayoutConfig.Default,
        /**
         * Per @Ores T2.7 §1.3: summary rows render in muted body
         * text colour, slightly dimmer for the trailing `…N more`
         * line. Production callers pass theme-resolved values; tests
         * accept the [defaultLightForTesting] defaults.
         */
        summaryRowPalette: SummaryRowPalette = SummaryRowPalette.defaultLightForTesting,
        /**
         * Theme-derived [GraphPalette] for status-accent colour
         * computation. Per @Lily PR #24 review (`4422760595`)
         * blocker 2: must NOT default to
         * `GraphPalette.defaultLightForTesting` inside the build
         * loop — that bypasses the production theme palette derived
         * via `rememberGraphPalette()`. Compose adapter passes the
         * theme palette in.
         *
         * Default is `defaultLightForTesting` only so unit-test
         * call sites (no theme available) don't have to construct
         * a palette per test.
         */
        graphPalette: GraphPalette = GraphPalette.defaultLightForTesting,
    ): RenderPlan {
        val nodeById = graph.nodes.associateBy { it.id }
        val visibleNodeIds = layoutResult.nodes.entries
            .filter { (_, rect) -> visibleBounds == null || rect.intersects(visibleBounds) }
            .map { it.key }
            .toSet()

        val commands = mutableListOf<DrawCommand>()

        // Edges first so node bodies layer over them near each port.
        for ((linkId, path) in layoutResult.edges) {
            // Skip edges whose endpoints both fall outside the
            // visible viewport. (An edge with one end inside still
            // draws so the user sees the connection terminate.)
            if (visibleBounds != null) {
                val startIn = visibleBounds.contains(path.start)
                val endIn = visibleBounds.contains(path.end)
                if (!startIn && !endIn) continue
            }
            // Find link record so we can colour by type.
            val link = graph.links.firstOrNull { it.linkId == linkId } ?: continue
            val source = nodeById[link.sourceNodeId] ?: continue
            val sourcePort = source.outputs.getOrNull(link.sourceSlot)
                ?: com.comfymobile.presentation.graph.NodePort(link.sourceSlot, "", link.type)
            commands.add(
                DrawCommand.Edge(
                    linkId = linkId,
                    path = path,
                    argb = resolvePortStyle(sourcePort).argb,
                    widthDp = 2f,
                ),
            )
        }

        // Nodes second so they paint on top of edges.
        for (nodeId in visibleNodeIds) {
            val node = nodeById[nodeId] ?: continue
            val rect = layoutResult.nodes[nodeId] ?: continue
            val style = resolveStyle(node)
            val titleSpec = resolveTitle(node)

            // Body
            commands.add(
                DrawCommand.NodeBody(
                    nodeId = nodeId,
                    rect = rect,
                    fillArgb = style.fillArgb,
                    borderArgb = style.borderArgb,
                    borderWidthDp = style.borderWidthDp,
                    cornerRadiusDp = 12f,
                ),
            )
            // Title bar (a strip across the top of the node)
            commands.add(
                DrawCommand.NodeTitle(
                    nodeId = nodeId,
                    rect = NodeRect(rect.x, rect.y, rect.width, config.titleHeight),
                    titleArgb = style.titleArgb,
                    text = titleSpec.text,
                    italic = titleSpec.italic,
                    statusBadge = style.statusBadge,
                    statusAccentArgb = NodeStyleResolver.statusAccentArgb(
                        runtimeStatus = NodeRuntimeStatus.IDLE.takeIf { style.statusBadge == StatusBadge.NONE }
                            ?: when (style.statusBadge) {
                                StatusBadge.SPINNER -> NodeRuntimeStatus.RUNNING
                                StatusBadge.DONE -> NodeRuntimeStatus.DONE
                                StatusBadge.CACHED -> NodeRuntimeStatus.CACHED
                                StatusBadge.ERROR -> NodeRuntimeStatus.ERROR
                                StatusBadge.NONE -> NodeRuntimeStatus.IDLE
                            },
                        // Theme-derived palette flows through from
                        // the caller (Compose: rememberGraphPalette();
                        // tests: defaultLightForTesting).
                        palette = graphPalette,
                    ),
                ),
            )

            // Summary rows (only on FULL-body, descriptor-known nodes
            // — per @Ores §1.3, TITLE_ONLY nodes do not show rows).
            if (style.bodyMode == BodyMode.FULL) {
                val rows = resolveSummaryRows(node)
                val firstRowY = rect.y + config.titleHeight + config.nodeBodyPadding
                val rowPitch = summaryRowPalette.fontSizeSp + 4f
                for ((rowIndex, entry) in rows.withIndex()) {
                    commands.add(
                        DrawCommand.SummaryRow(
                            nodeId = nodeId,
                            rowIndex = rowIndex,
                            origin = Position(
                                x = rect.x + config.nodeBodyPadding,
                                y = firstRowY + rowIndex * rowPitch,
                            ),
                            text = entry.text,
                            emphasis = entry.emphasis,
                            textArgb = when (entry.emphasis) {
                                SummaryEntry.Emphasis.PARAM -> summaryRowPalette.paramTextArgb
                                SummaryEntry.Emphasis.MORE_HINT -> summaryRowPalette.moreHintTextArgb
                            },
                            fontSizeSp = summaryRowPalette.fontSizeSp,
                        ),
                    )
                }
            }

            // Ports (only on FULL-body nodes; TITLE_ONLY has none)
            if (style.bodyMode == BodyMode.FULL) {
                for ((idx, port) in node.inputs.withIndex()) {
                    val portCenter = portCenter(rect, idx, isOutput = false, portCount = node.inputs.size, config = config)
                    commands.add(
                        DrawCommand.NodePort(
                            nodeId = nodeId,
                            portIndex = idx,
                            center = portCenter,
                            radiusDp = 5f,
                            argb = resolvePortStyle(port).argb,
                            isOutput = false,
                        ),
                    )
                }
                for ((idx, port) in node.outputs.withIndex()) {
                    val portCenter = portCenter(rect, idx, isOutput = true, portCount = node.outputs.size, config = config)
                    commands.add(
                        DrawCommand.NodePort(
                            nodeId = nodeId,
                            portIndex = idx,
                            center = portCenter,
                            radiusDp = 5f,
                            argb = resolvePortStyle(port).argb,
                            isOutput = true,
                        ),
                    )
                }
            }
        }
        return RenderPlan(commands)
    }

    /**
     * Mirror of [GraphLayout]'s internal port placement so command
     * coordinates match edge endpoints exactly. Kept here as a small
     * utility rather than exporting from `GraphLayout` so the layout
     * file's public surface stays tight.
     */
    private fun portCenter(
        rect: NodeRect,
        slotIndex: Int,
        isOutput: Boolean,
        portCount: Int,
        config: LayoutConfig,
    ): Position {
        val x = if (isOutput) rect.right else rect.x
        val safeIndex = slotIndex.coerceIn(0, (portCount - 1).coerceAtLeast(0))
        val firstRowY = rect.y + config.titleHeight + config.nodeBodyPadding +
            config.portRowHeight / 2f
        val y = firstRowY + safeIndex * config.portRowHeight
        return Position(x, y)
    }
}

/**
 * Resolved title content for a node — pre-localised by the caller so
 * the render plan stays language-agnostic (tests assert exact text).
 *
 * @property text Display text. For unknown nodes the caller passes
 *   the raw `class_type`; for known nodes the descriptor's localised
 *   display name.
 * @property italic True when the node is unknown — italic styling is
 *   the visual signal per @Ores T2.7 §1.1.
 */
data class NodeTitleSpec(
    val text: String,
    val italic: Boolean,
)

// ---------------------------------------------------------------- math helpers

/** True when this rect contains [point] (closed). */
internal fun Rect.contains(point: Position): Boolean =
    point.x in left..right && point.y in top..bottom

/** True when [other] intersects this rect (axis-aligned). */
internal fun NodeRect.intersects(other: Rect): Boolean =
    !(right < other.left || x > other.right || bottom < other.top || y > other.bottom)
