package com.comfymobile.presentation.graph

import kotlinx.serialization.Serializable

/**
 * Output of [GraphLayout.layout]. Pure-data, kotlinx-Serializable —
 * fixtures and golden tests can compare values without a UI.
 *
 * Per @Lily PR #19 thread (`4da46760`) T2.1a guidance: the layout
 * output keys nodes by id and edges by id so callers (T2.1b
 * viewport-virtualisation, hit-testing, render plan builder) can do
 * per-element work without a quadratic scan.
 */
@Serializable
data class LayoutResult(
    /** `nodeId` → axis-aligned bounding box in workflow-world units. */
    val nodes: Map<String, NodeRect>,
    /** `linkId` → bezier path in workflow-world units. */
    val edges: Map<String, EdgePath>,
    /** Tight bounding box of all node rects. Empty rect when graph has no nodes. */
    val contentBounds: Rect,
)

/**
 * Axis-aligned rectangle in workflow-world units.
 *
 * Coordinate convention: `x` increases right, `y` increases down,
 * matching ComfyUI's editor save and Compose's drawscope.
 */
@Serializable
data class NodeRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

/**
 * Cubic-bezier path between two ports.
 *
 * Stored as 4 explicit control points so the render layer can either
 * draw it as a smooth bezier (T2.1a / steady state) or replace with
 * a polyline approximation during drag (T2.1b §1.5 fallback) without
 * recomputing.
 *
 * @property start Source port screen position.
 * @property control1 First handle — by default a horizontal lead-out
 *   from [start] proportional to the X distance to [end]. The render
 *   layer can substitute a different handle for fancy routing.
 * @property control2 Second handle, mirror of [control1] near [end].
 * @property end Target port screen position.
 */
@Serializable
data class EdgePath(
    val start: Position,
    val control1: Position,
    val control2: Position,
    val end: Position,
) {
    /** Straight-line approximation. Used by the drag-time direct-line fallback. */
    fun straightLine(): List<Position> = listOf(start, end)
}

/**
 * Axis-aligned rectangle (left/top/right/bottom convention to make
 * intersection / containment math obvious in tests).
 */
@Serializable
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    companion object {
        val Zero: Rect = Rect(0f, 0f, 0f, 0f)
    }
}
