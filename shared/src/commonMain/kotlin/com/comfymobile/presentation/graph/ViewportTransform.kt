package com.comfymobile.presentation.graph

/**
 * Pure-function viewport math.
 *
 * Coordinate convention (must stay consistent across the render
 * pipeline):
 *  - **World space** — `Position`/`NodeRect` units from
 *    [GraphLayout.layout]. Positive x = right, positive y = down,
 *    same as ComfyUI editor.
 *  - **Screen space** — pixel coordinates inside the Compose
 *    `Canvas` modifier, also y-down.
 *  - **Transform** — `screen = (world + pan) * zoom`. Pan is stored
 *    in world units inside [GestureState], so panning by 100 world
 *    units shifts the camera the same world distance regardless of
 *    current zoom (intuitive — drag distance feels consistent at
 *    the same world scale).
 *
 * Inversion: `world = screen / zoom - pan`. The reducer derives
 * focus-anchored zoom and fit-to-rect from these two equations;
 * [HitTester] uses the inverse for screen-to-world hit testing.
 *
 * Per @Lily PR #24 thread review #1 (msg `b025c831`): both
 * directions are exposed as pure functions with unit-test coverage
 * including pan+zoom combos, boundary cases, negative coordinates,
 * and clicks outside [LayoutResult.contentBounds].
 */
object ViewportTransform {

    /** Convert a world-space point to screen-space using the gesture state. */
    fun worldToScreen(world: Position, gesture: GestureState): Position = Position(
        x = (world.x + gesture.panX) * gesture.zoomScale,
        y = (world.y + gesture.panY) * gesture.zoomScale,
    )

    /** Convert a screen-space point to world-space using the gesture state. */
    fun screenToWorld(screen: Position, gesture: GestureState): Position {
        val zoom = gesture.zoomScale.coerceAtLeast(0.0001f)
        return Position(
            x = screen.x / zoom - gesture.panX,
            y = screen.y / zoom - gesture.panY,
        )
    }

    /**
     * The world-space rectangle currently visible inside a screen-
     * space canvas of [canvasWidthPx] × [canvasHeightPx].
     *
     * Inflated by [bufferWorldUnits] on each side so the viewport
     * virtualisation in [RenderPlanBuilder] keeps a 1-screen-ish
     * buffer around the visible area — nodes about to scroll into
     * view stay in the plan, avoiding visible pop-in on pan.
     */
    fun computeVisibleBounds(
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        gesture: GestureState,
        bufferWorldUnits: Float = 200f,
    ): Rect {
        // World rect = inverse-transform of (0,0)→(canvasW, canvasH).
        val topLeft = screenToWorld(Position(0f, 0f), gesture)
        val bottomRight = screenToWorld(Position(canvasWidthPx, canvasHeightPx), gesture)
        return Rect(
            left = topLeft.x - bufferWorldUnits,
            top = topLeft.y - bufferWorldUnits,
            right = bottomRight.x + bufferWorldUnits,
            bottom = bottomRight.y + bufferWorldUnits,
        )
    }
}
