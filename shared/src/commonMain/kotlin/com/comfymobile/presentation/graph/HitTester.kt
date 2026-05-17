package com.comfymobile.presentation.graph

/**
 * A node found at a hit-test point. Currently nodes are the only
 * hit target; T2.1b doesn't surface edge / port picking — the
 * single-tap UX per @Ores T2.7 §1.2 is "tap node to select", with
 * long-press triggering the param drawer (T2.2). Edges aren't
 * selectable in Phase 2.
 */
data class HitTarget(
    val nodeId: String,
    /** The node's world-space rect at the time of the hit. */
    val rect: NodeRect,
)

/**
 * Pure-function hit testing.
 *
 * Per @Lily PR #24 thread review #1 (msg `b025c831`): exposes the
 * screen→world transform via [ViewportTransform.screenToWorld] and
 * walks the layout's node map for an axis-aligned containment check.
 * Iteration order is reverse — last-laid-out node wins on overlap,
 * mirroring the painter's order (later draws on top, so later wins
 * on tap).
 */
object HitTester {

    /**
     * @return The topmost [HitTarget] under [screenX]/[screenY], or
     *   `null` when the point doesn't fall on any node.
     */
    fun hitTest(
        screenX: Float,
        screenY: Float,
        layout: LayoutResult,
        gesture: GestureState,
    ): HitTarget? {
        if (layout.nodes.isEmpty()) return null
        val worldPoint = ViewportTransform.screenToWorld(Position(screenX, screenY), gesture)
        // Reverse order: nodes painted later appear on top, so the
        // last entry in the layout map wins.
        for ((nodeId, rect) in layout.nodes.entries.reversed()) {
            if (rect.containsWorldPoint(worldPoint.x, worldPoint.y)) {
                return HitTarget(nodeId = nodeId, rect = rect)
            }
        }
        return null
    }
}

private fun NodeRect.containsWorldPoint(wx: Float, wy: Float): Boolean =
    wx in x..right && wy in y..bottom
