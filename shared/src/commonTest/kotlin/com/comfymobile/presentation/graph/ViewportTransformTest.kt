package com.comfymobile.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function viewport math tests. Per @Lily PR #24 thread review
 * #1 (msg `b025c831`): coverage includes pan+zoom combos, boundary
 * points, negative coordinates, and inverse round-trip.
 */
class ViewportTransformTest {

    @Test fun identity_state_is_screen_world_identity() {
        val world = Position(123f, 456f)
        val screen = ViewportTransform.worldToScreen(world, GestureState.Identity)
        assertEquals(world.x, screen.x)
        assertEquals(world.y, screen.y)
    }

    @Test fun worldToScreen_inverse_of_screenToWorld_with_pan_and_zoom() {
        val state = GestureState(panX = 17f, panY = -42f, zoomScale = 1.7f)
        val world = Position(200f, -150f)
        val screen = ViewportTransform.worldToScreen(world, state)
        val roundtrip = ViewportTransform.screenToWorld(screen, state)
        assertEquals(world.x, roundtrip.x, 0.001f)
        assertEquals(world.y, roundtrip.y, 0.001f)
    }

    @Test fun negative_world_coordinates_handled() {
        val state = GestureState(zoomScale = 0.5f, panX = 100f, panY = 100f)
        val world = Position(-50f, -200f)
        val screen = ViewportTransform.worldToScreen(world, state)
        // worldToScreen: ((-50 + 100) * 0.5, (-200 + 100) * 0.5) = (25, -50)
        assertEquals(25f, screen.x, 0.001f)
        assertEquals(-50f, screen.y, 0.001f)
        val back = ViewportTransform.screenToWorld(screen, state)
        assertEquals(world.x, back.x, 0.001f)
        assertEquals(world.y, back.y, 0.001f)
    }

    @Test fun zoom_at_low_extreme_does_not_divide_by_zero() {
        // Use min zoom; screenToWorld should still produce finite result.
        val state = GestureState(zoomScale = GestureState.ZOOM_MIN)
        val world = ViewportTransform.screenToWorld(Position(100f, 200f), state)
        assertTrue(world.x.isFinite())
        assertTrue(world.y.isFinite())
    }

    @Test fun computeVisibleBounds_at_identity_matches_canvas_dimensions_with_buffer() {
        val bounds = ViewportTransform.computeVisibleBounds(
            canvasWidthPx = 800f,
            canvasHeightPx = 600f,
            gesture = GestureState.Identity,
            bufferWorldUnits = 0f, // turn off buffer for the exact assertion
        )
        assertEquals(0f, bounds.left)
        assertEquals(0f, bounds.top)
        assertEquals(800f, bounds.right)
        assertEquals(600f, bounds.bottom)
    }

    @Test fun computeVisibleBounds_with_buffer_inflates_on_all_sides() {
        val bounds = ViewportTransform.computeVisibleBounds(
            canvasWidthPx = 800f,
            canvasHeightPx = 600f,
            gesture = GestureState.Identity,
            bufferWorldUnits = 200f,
        )
        assertEquals(-200f, bounds.left)
        assertEquals(-200f, bounds.top)
        assertEquals(1000f, bounds.right)
        assertEquals(800f, bounds.bottom)
    }

    @Test fun computeVisibleBounds_at_zoom_2_shrinks_visible_world_area() {
        // At 2x zoom, screen 800x600 shows only 400x300 of world (centered at world origin).
        val bounds = ViewportTransform.computeVisibleBounds(
            canvasWidthPx = 800f,
            canvasHeightPx = 600f,
            gesture = GestureState(zoomScale = 2f),
            bufferWorldUnits = 0f,
        )
        assertEquals(0f, bounds.left)
        assertEquals(0f, bounds.top)
        assertEquals(400f, bounds.right, 0.001f)
        assertEquals(300f, bounds.bottom, 0.001f)
    }

    @Test fun computeVisibleBounds_with_pan_shifts_world_rect() {
        // Pan world right by 100 (panX=100) → world rect shifts left
        // by 100 (camera moved right, so world left edge is at -100).
        val bounds = ViewportTransform.computeVisibleBounds(
            canvasWidthPx = 800f,
            canvasHeightPx = 600f,
            gesture = GestureState(panX = 100f, panY = 0f, zoomScale = 1f),
            bufferWorldUnits = 0f,
        )
        assertEquals(-100f, bounds.left)
        assertEquals(700f, bounds.right)
    }
}
