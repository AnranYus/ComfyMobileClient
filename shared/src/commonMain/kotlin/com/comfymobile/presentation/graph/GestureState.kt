package com.comfymobile.presentation.graph

import kotlinx.serialization.Serializable

/**
 * Snapshot of the user's active interaction with the graph canvas.
 *
 * Drives three things:
 *  1. **Viewport transform** — [panX]/[panY]/[zoomScale] are the
 *     world-to-screen affine. [ViewportTransform] inverts them when
 *     translating screen-space gesture coordinates back to world
 *     space (hit testing).
 *  2. **LOD downgrade during interaction** — when [isInteracting] is
 *     true, the render layer flips
 *     [DrawCommand.Edge.straightLineFallback] to `true` so connection
 *     lines render as straight segments. Per @Ores T2.7 §1.5/§1.8
 *     locked LOD contract (PR #21 msg `26c8f2f3`): any active
 *     pan/zoom/node-drag gesture downgrades bezier→straight; release
 *     restores bezier.
 *  3. **Selection** — [selectedNodeId] feeds back to the render layer
 *     so the selected node renders with the primary outline (per
 *     @Ores §1.1).
 *
 * Pure data — no Compose `Offset` / `Float` `MutableState` so the
 * gesture state can be serialised, tested, and snapshotted off the
 * UI thread.
 *
 * @property panX World-space x offset of the viewport, in workflow
 *   units. Positive moves the world right (camera goes left).
 * @property panY Same for y.
 * @property zoomScale Zoom factor. 1.0 = identity. Clamped to
 *   `[ZOOM_MIN, ZOOM_MAX]` by [GestureState.copyWithClampedZoom];
 *   per @Ores T2.7 §1.7 range is 0.5x–3x.
 * @property selectedNodeId `null` when no node is selected. Single
 *   selection (multi-select is out of Phase 2 scope per ADR-0003).
 * @property isInteracting `true` while pointer is down for a pan,
 *   zoom, or node-drag gesture; `false` after release. Drives the
 *   bezier→straight LOD toggle. Tap and long-press do NOT set this
 *   true — they're momentary, not continuous gestures.
 */
@Serializable
data class GestureState(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoomScale: Float = 1f,
    val selectedNodeId: String? = null,
    val isInteracting: Boolean = false,
) {
    /** Constrain [zoomScale] to the @Ores §1.7 spec range. */
    fun copyWithClampedZoom(zoom: Float): GestureState =
        copy(zoomScale = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX))

    companion object {
        /** Per @Ores T2.7 §1.7 — pinch zoom lower bound. */
        const val ZOOM_MIN: Float = 0.5f

        /** Per @Ores T2.7 §1.7 — pinch zoom upper bound. */
        const val ZOOM_MAX: Float = 3f

        /** Identity state — fresh user, no gesture, no selection. */
        val Identity: GestureState = GestureState()
    }
}
