package com.comfymobile.presentation.graph

/**
 * Configuration for [GraphLayout.layout]. Pure values, no platform
 * types — keeps the layout function fully deterministic and unit-
 * testable. Production callers pick concrete numbers based on the
 * Compose theme; tests can use [Default] freely.
 *
 * Per @Ores T2.7 §1.1 spec:
 *  - default node width 200dp; tests / fixtures use 200f world units.
 *  - default node height ≈ 40dp title + N × 28dp port row + padding.
 *  - port spacing 28dp.
 */
data class LayoutConfig(
    /** Width applied to nodes whose editor save did not record a size. */
    val defaultNodeWidth: Float = 200f,
    /** Title-bar height (per @Ores §1.2). */
    val titleHeight: Float = 40f,
    /** Vertical pitch of port rows. */
    val portRowHeight: Float = 28f,
    /** Padding inside the node body around port rows. */
    val nodeBodyPadding: Float = 8f,
    /** Horizontal gap between auto-layout columns. */
    val columnGap: Float = 80f,
    /** Vertical gap between auto-layout rows in the same column. */
    val rowGap: Float = 24f,
    /** Origin for auto-layout placement when no `pos` is recorded. */
    val autoLayoutOrigin: Position = Position(0f, 0f),
) {
    companion object {
        val Default: LayoutConfig = LayoutConfig()
    }
}

/**
 * Pure function: [ParsedUiGraph] + [LayoutConfig] → [LayoutResult].
 *
 * No IO, no clock, no Compose imports — by construction this can run
 * inside a benchmark fixture (per @Lily PR #19 thread `4da46760`
 * point 1) or a unit test without any UI scaffold.
 *
 * Placement strategy:
 *  1. **Honour ComfyUI's saved positions** if the editor save
 *     populated `pos` for every node — that's what the desktop user
 *     arranged, and we don't second-guess it.
 *  2. **BFS auto-layout** otherwise: topologically sort by link
 *     dependency and lay nodes out left-to-right (column = topological
 *     depth, row = order within depth). Per @Ores T2.7 §1.6.
 *
 * Port positions ([buildEdgePath]) are derived from the resulting
 * node rects so they always match the rendered geometry.
 */
object GraphLayout {

    fun layout(
        graph: ParsedUiGraph,
        config: LayoutConfig = LayoutConfig.Default,
    ): LayoutResult {
        if (graph.nodes.isEmpty()) {
            return LayoutResult(emptyMap(), emptyMap(), Rect.Zero)
        }

        val nodes = layoutNodes(graph, config)
        val edges = layoutEdges(graph, nodes, config)
        val bounds = computeBounds(nodes.values)
        return LayoutResult(nodes = nodes, edges = edges, contentBounds = bounds)
    }

    // ---------------------------------------------------------------- nodes

    private fun layoutNodes(
        graph: ParsedUiGraph,
        config: LayoutConfig,
    ): Map<String, NodeRect> {
        // Strategy 1: every node has an originalPos → honour them.
        return if (graph.nodes.all { it.originalPos != null }) {
            graph.nodes.associate { node ->
                val pos = node.originalPos!!
                node.id to nodeRectFor(node, pos, config)
            }
        } else {
            // Strategy 2: BFS auto-layout.
            autoLayoutBfs(graph, config)
        }
    }

    /**
     * Produce a [NodeRect] for [node] anchored at [pos]. Width / height
     * fall back to [config] defaults when the editor save didn't
     * record an explicit size.
     */
    private fun nodeRectFor(
        node: ParsedNode,
        pos: Position,
        config: LayoutConfig,
    ): NodeRect {
        val size = node.originalSize ?: defaultSizeFor(node, config)
        return NodeRect(x = pos.x, y = pos.y, width = size.width, height = size.height)
    }

    /**
     * Derive a height from the port count when the editor save didn't
     * record a `size`. This is also what auto-layout uses.
     */
    private fun defaultSizeFor(node: ParsedNode, config: LayoutConfig): Size {
        val portRows = maxOf(node.inputs.size, node.outputs.size)
        val height = config.titleHeight +
            config.nodeBodyPadding * 2 +
            portRows * config.portRowHeight
        return Size(width = config.defaultNodeWidth, height = height)
    }

    /**
     * Layered left-to-right BFS: each node's column = max column of
     * any source node + 1. Within a column, nodes stack top-to-bottom
     * in the order they appear in [ParsedUiGraph.nodes].
     *
     * Cycles in `links` are broken arbitrarily — every node lands in
     * exactly one column.
     */
    private fun autoLayoutBfs(
        graph: ParsedUiGraph,
        config: LayoutConfig,
    ): Map<String, NodeRect> {
        val nodeById = graph.nodes.associateBy { it.id }
        val incomingByTarget = graph.links.groupBy { it.targetNodeId }
        val depth = mutableMapOf<String, Int>()

        // Iterative depth assignment: keep refining until depths
        // stabilise or we hit a generous cap (100 passes — defensively
        // bounded so a malformed cycle can't loop forever).
        repeat(100) {
            var changed = false
            for (node in graph.nodes) {
                val incoming = incomingByTarget[node.id].orEmpty()
                val newDepth = if (incoming.isEmpty()) {
                    0
                } else {
                    incoming.mapNotNull { depth[it.sourceNodeId] }.maxOrNull()?.let { it + 1 } ?: 0
                }
                if (depth[node.id] != newDepth) {
                    depth[node.id] = newDepth
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        for (node in graph.nodes) {
            if (node.id !in depth) depth[node.id] = 0
        }

        // Group by column and iterate columns in ascending order
        // (commonMain has no `toSortedMap`, so sort the entry list).
        val byColumn = graph.nodes.groupBy { depth[it.id] ?: 0 }
        val sortedColumns = byColumn.entries.sortedBy { it.key }

        val rects = LinkedHashMap<String, NodeRect>(graph.nodes.size)
        var columnX = config.autoLayoutOrigin.x
        for ((_, columnNodes) in sortedColumns) {
            var rowY = config.autoLayoutOrigin.y
            var maxColumnWidth = 0f
            for (node in columnNodes) {
                val size = node.originalSize ?: defaultSizeFor(node, config)
                rects[node.id] = NodeRect(columnX, rowY, size.width, size.height)
                rowY += size.height + config.rowGap
                if (size.width > maxColumnWidth) maxColumnWidth = size.width
            }
            columnX += maxColumnWidth + config.columnGap
        }
        return rects
    }

    // ---------------------------------------------------------------- edges

    private fun layoutEdges(
        graph: ParsedUiGraph,
        nodes: Map<String, NodeRect>,
        config: LayoutConfig,
    ): Map<String, EdgePath> {
        val nodeById = graph.nodes.associateBy { it.id }
        return graph.links.mapNotNull { link ->
            val source = nodeById[link.sourceNodeId] ?: return@mapNotNull null
            val target = nodeById[link.targetNodeId] ?: return@mapNotNull null
            val sourceRect = nodes[link.sourceNodeId] ?: return@mapNotNull null
            val targetRect = nodes[link.targetNodeId] ?: return@mapNotNull null

            val srcPort = portPosition(
                rect = sourceRect,
                slotIndex = link.sourceSlot,
                isOutput = true,
                portCount = source.outputs.size.coerceAtLeast(1),
                config = config,
            )
            val dstPort = portPosition(
                rect = targetRect,
                slotIndex = link.targetSlot,
                isOutput = false,
                portCount = target.inputs.size.coerceAtLeast(1),
                config = config,
            )
            link.linkId to buildEdgePath(srcPort, dstPort)
        }.toMap()
    }

    /**
     * Output ports sit on the right edge of the node, input ports on
     * the left edge. Vertically distributed below the title bar.
     */
    private fun portPosition(
        rect: NodeRect,
        slotIndex: Int,
        isOutput: Boolean,
        portCount: Int,
        config: LayoutConfig,
    ): Position {
        val x = if (isOutput) rect.right else rect.x
        val safeIndex = slotIndex.coerceIn(0, portCount - 1)
        val firstRowY = rect.y + config.titleHeight + config.nodeBodyPadding +
            config.portRowHeight / 2f
        val y = firstRowY + safeIndex * config.portRowHeight
        return Position(x, y)
    }

    /**
     * Smooth bezier control points: handles extend horizontally
     * outward by half the X distance so the curve enters/exits each
     * port at right angles to the node edge — which is what users
     * expect from desktop ComfyUI.
     */
    private fun buildEdgePath(start: Position, end: Position): EdgePath {
        val handleStretch = (end.x - start.x).coerceAtLeast(0f) / 2f
        return EdgePath(
            start = start,
            control1 = Position(start.x + handleStretch, start.y),
            control2 = Position(end.x - handleStretch, end.y),
            end = end,
        )
    }

    // ---------------------------------------------------------------- bounds

    private fun computeBounds(rects: Collection<NodeRect>): Rect {
        if (rects.isEmpty()) return Rect.Zero
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (rect in rects) {
            if (rect.x < minX) minX = rect.x
            if (rect.y < minY) minY = rect.y
            if (rect.right > maxX) maxX = rect.right
            if (rect.bottom > maxY) maxY = rect.bottom
        }
        return Rect(left = minX, top = minY, right = maxX, bottom = maxY)
    }
}
