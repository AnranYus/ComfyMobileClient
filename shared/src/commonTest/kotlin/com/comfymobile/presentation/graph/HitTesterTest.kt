package com.comfymobile.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HitTesterTest {

    private fun layout(vararg nodes: Pair<String, NodeRect>): LayoutResult =
        LayoutResult(
            nodes = linkedMapOf(*nodes),
            edges = emptyMap(),
            contentBounds = Rect.Zero,
        )

    @Test fun hit_test_returns_null_when_layout_empty() {
        val out = HitTester.hitTest(
            screenX = 100f,
            screenY = 100f,
            layout = layout(),
            gesture = GestureState.Identity,
        )
        assertNull(out)
    }

    @Test fun hit_test_returns_null_when_point_outside_all_nodes() {
        val out = HitTester.hitTest(
            screenX = 1000f,
            screenY = 1000f,
            layout = layout("n" to NodeRect(0f, 0f, 100f, 100f)),
            gesture = GestureState.Identity,
        )
        assertNull(out)
    }

    @Test fun hit_test_returns_node_under_point_at_identity_state() {
        val out = HitTester.hitTest(
            screenX = 50f,
            screenY = 50f,
            layout = layout("n" to NodeRect(0f, 0f, 100f, 100f)),
            gesture = GestureState.Identity,
        )
        assertEquals("n", out?.nodeId)
    }

    @Test fun hit_test_applies_pan_inverse_to_resolve_world_point() {
        // Node at world (200..300, 200..300). User has panned the world
        // so the same node is now at screen (100..200, 100..200) at
        // 1x zoom. Tapping screen (150, 150) should hit "n".
        val state = GestureState(panX = -100f, panY = -100f, zoomScale = 1f)
        val out = HitTester.hitTest(
            screenX = 150f,
            screenY = 150f,
            layout = layout("n" to NodeRect(200f, 200f, 100f, 100f)),
            gesture = state,
        )
        assertEquals("n", out?.nodeId)
    }

    @Test fun hit_test_applies_zoom_inverse_to_resolve_world_point() {
        // At 2x zoom, screen (300, 300) corresponds to world (150, 150)
        // when pan is zero. The node at world (100..200, 100..200) should hit.
        val state = GestureState(zoomScale = 2f)
        val out = HitTester.hitTest(
            screenX = 300f,
            screenY = 300f,
            layout = layout("n" to NodeRect(100f, 100f, 100f, 100f)),
            gesture = state,
        )
        assertEquals("n", out?.nodeId)
    }

    @Test fun hit_test_handles_combined_pan_and_zoom() {
        // pan = (-100, -100), zoom = 2.
        // screen → world: world = screen/2 - pan = screen/2 + 100
        // Screen (50, 50) → world (125, 125). Node (100..200, 100..200) contains it.
        val state = GestureState(panX = -100f, panY = -100f, zoomScale = 2f)
        val out = HitTester.hitTest(
            screenX = 50f,
            screenY = 50f,
            layout = layout("n" to NodeRect(100f, 100f, 100f, 100f)),
            gesture = state,
        )
        assertEquals("n", out?.nodeId)
    }

    @Test fun hit_test_returns_topmost_node_on_overlap() {
        // Two overlapping nodes: "back" then "front" in iteration order.
        // The latter wins (painter's algorithm: drawn later is on top).
        val out = HitTester.hitTest(
            screenX = 50f,
            screenY = 50f,
            layout = layout(
                "back" to NodeRect(0f, 0f, 100f, 100f),
                "front" to NodeRect(0f, 0f, 100f, 100f),
            ),
            gesture = GestureState.Identity,
        )
        assertEquals("front", out?.nodeId)
    }

    @Test fun hit_test_at_negative_screen_coordinates_returns_null_when_no_node_there() {
        val out = HitTester.hitTest(
            screenX = -10f,
            screenY = -10f,
            layout = layout("n" to NodeRect(0f, 0f, 100f, 100f)),
            gesture = GestureState.Identity,
        )
        assertNull(out)
    }

    @Test fun hit_test_outside_contentBounds_returns_null() {
        // Layout has contentBounds but the click is far outside.
        val out = HitTester.hitTest(
            screenX = 10_000f,
            screenY = 10_000f,
            layout = LayoutResult(
                nodes = mapOf("n" to NodeRect(0f, 0f, 100f, 100f)),
                edges = emptyMap(),
                contentBounds = Rect(0f, 0f, 100f, 100f),
            ),
            gesture = GestureState.Identity,
        )
        assertNull(out)
    }
}
