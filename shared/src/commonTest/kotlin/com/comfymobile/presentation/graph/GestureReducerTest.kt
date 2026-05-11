package com.comfymobile.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function reducer tests. Per @Lily PR #24 thread review #3
 * (msg `b025c831`): exhaustive state-transition coverage lives
 * here so Compose tests can be thin wiring assertions only.
 */
class GestureReducerTest {

    // ---------------------------------------------------------------- Pan

    @Test fun pan_at_zoom_1_translates_screen_delta_directly_to_world_pan() {
        val s = GestureState.Identity
        val out = GestureReducer.reduce(s, GestureIntent.Pan(deltaScreenX = 50f, deltaScreenY = -30f))
        assertEquals(50f, out.panX)
        assertEquals(-30f, out.panY)
    }

    @Test fun pan_at_zoom_2_halves_world_pan_for_same_screen_delta() {
        // Drag 50 screen px at 2x zoom → world moves 25 units (intuitive:
        // a screen-pixel drag corresponds to a smaller world-distance when
        // zoomed in).
        val s = GestureState(zoomScale = 2f)
        val out = GestureReducer.reduce(s, GestureIntent.Pan(50f, -30f))
        assertEquals(25f, out.panX, "pan x at 2x zoom should be 25")
        assertEquals(-15f, out.panY, "pan y at 2x zoom should be -15")
    }

    @Test fun pan_accumulates_across_dispatches() {
        var state = GestureState.Identity
        state = GestureReducer.reduce(state, GestureIntent.Pan(10f, 20f))
        state = GestureReducer.reduce(state, GestureIntent.Pan(5f, -2f))
        assertEquals(15f, state.panX)
        assertEquals(18f, state.panY)
    }

    // ---------------------------------------------------------------- Zoom

    @Test fun zoom_clamped_to_min_max_range() {
        val tooFar = GestureReducer.reduce(GestureState.Identity, GestureIntent.Zoom(factor = 10f, 0f, 0f))
        assertEquals(GestureState.ZOOM_MAX, tooFar.zoomScale)
        val tooClose = GestureReducer.reduce(GestureState(zoomScale = 1f), GestureIntent.Zoom(factor = 0.1f, 0f, 0f))
        assertEquals(GestureState.ZOOM_MIN, tooClose.zoomScale)
    }

    @Test fun zoom_at_clamp_returns_state_unchanged() {
        // Already at MAX, factor > 1 is a no-op (state identity)
        val atMax = GestureState(zoomScale = GestureState.ZOOM_MAX, panX = 5f, panY = 7f)
        val out = GestureReducer.reduce(atMax, GestureIntent.Zoom(factor = 2f, 100f, 200f))
        assertEquals(atMax, out, "no-op zoom should not mutate any field including pan")
    }

    @Test fun zoom_with_focus_keeps_world_point_under_focus_invariant() {
        // Choose a state, a focus point, and a zoom factor; verify
        // ViewportTransform.screenToWorld(focus) returns the same
        // world coordinate before and after.
        val before = GestureState(panX = 10f, panY = 20f, zoomScale = 1f)
        val focus = Position(300f, 200f)
        val worldBefore = ViewportTransform.screenToWorld(focus, before)
        val after = GestureReducer.reduce(
            before,
            GestureIntent.Zoom(factor = 2f, focusScreenX = focus.x, focusScreenY = focus.y),
        )
        val worldAfter = ViewportTransform.screenToWorld(focus, after)
        assertEquals(worldBefore.x, worldAfter.x, 0.001f)
        assertEquals(worldBefore.y, worldAfter.y, 0.001f)
        assertEquals(2f, after.zoomScale)
    }

    // ---------------------------------------------------------------- Tap / LongPress

    @Test fun tap_with_hit_sets_selectedNodeId() {
        val out = GestureReducer.reduce(
            GestureState.Identity,
            GestureIntent.Tap(100f, 100f, hitNodeId = "n-7"),
        )
        assertEquals("n-7", out.selectedNodeId)
    }

    @Test fun tap_with_no_hit_clears_selection() {
        val state = GestureState(selectedNodeId = "n-5")
        val out = GestureReducer.reduce(state, GestureIntent.Tap(100f, 100f, hitNodeId = null))
        assertEquals(null, out.selectedNodeId)
    }

    @Test fun longpress_with_hit_sets_selection_same_as_tap() {
        val out = GestureReducer.reduce(
            GestureState.Identity,
            GestureIntent.LongPress(100f, 100f, hitNodeId = "n-3"),
        )
        assertEquals("n-3", out.selectedNodeId)
    }

    // ---------------------------------------------------------------- InteractionStart/End

    @Test fun interaction_start_sets_isInteracting_true() {
        val out = GestureReducer.reduce(GestureState.Identity, GestureIntent.InteractionStart)
        assertTrue(out.isInteracting)
    }

    @Test fun interaction_end_sets_isInteracting_false() {
        val out = GestureReducer.reduce(
            GestureState(isInteracting = true),
            GestureIntent.InteractionEnd,
        )
        assertEquals(false, out.isInteracting)
    }

    @Test fun interaction_start_preserves_other_fields() {
        val before = GestureState(panX = 5f, panY = 7f, zoomScale = 1.5f, selectedNodeId = "n-1")
        val after = GestureReducer.reduce(before, GestureIntent.InteractionStart)
        assertEquals(before.copy(isInteracting = true), after)
    }

    // ---------------------------------------------------------------- ResetView

    @Test fun reset_view_clears_pan_and_zoom_keeps_selection() {
        val before = GestureState(panX = 50f, panY = 30f, zoomScale = 2f, selectedNodeId = "n-1")
        val after = GestureReducer.reduce(before, GestureIntent.ResetView)
        assertEquals(0f, after.panX)
        assertEquals(0f, after.panY)
        assertEquals(1f, after.zoomScale)
        assertEquals("n-1", after.selectedNodeId, "Reset view should NOT clear selection")
    }

    // ---------------------------------------------------------------- FitTo

    @Test fun fit_to_empty_rect_is_noop() {
        val before = GestureState(panX = 5f, zoomScale = 1.5f)
        val after = GestureReducer.reduce(
            before,
            GestureIntent.FitTo(worldRect = Rect.Zero, canvasWidthPx = 800f, canvasHeightPx = 600f),
        )
        assertEquals(before, after)
    }

    @Test fun fit_to_rect_zooms_so_rect_fits_within_canvas() {
        // 200x100 rect, 800x600 canvas, with 60 padding on each side.
        // Effective rect = (200 + 120) x (100 + 120) = 320 x 220
        // zoomFitX = 800/320 = 2.5; zoomFitY = 600/220 ≈ 2.727
        // min = 2.5 → clamped down to ZOOM_MAX = 3, so 2.5 stays.
        val rect = Rect(left = 0f, top = 0f, right = 200f, bottom = 100f)
        val out = GestureReducer.reduce(
            GestureState.Identity,
            GestureIntent.FitTo(worldRect = rect, canvasWidthPx = 800f, canvasHeightPx = 600f),
        )
        assertEquals(2.5f, out.zoomScale, 0.01f)
    }

    @Test fun fit_to_huge_rect_clamps_to_zoom_min() {
        // Tiny canvas, huge rect → zoom would go below MIN.
        val rect = Rect(left = 0f, top = 0f, right = 10_000f, bottom = 10_000f)
        val out = GestureReducer.reduce(
            GestureState.Identity,
            GestureIntent.FitTo(worldRect = rect, canvasWidthPx = 100f, canvasHeightPx = 100f),
        )
        assertEquals(GestureState.ZOOM_MIN, out.zoomScale)
    }

    @Test fun fit_to_rect_centers_rect_on_canvas() {
        // Rect at (100, 100)..(300, 200), canvas 800x600.
        val rect = Rect(left = 100f, top = 100f, right = 300f, bottom = 200f)
        val out = GestureReducer.reduce(
            GestureState.Identity,
            GestureIntent.FitTo(worldRect = rect, canvasWidthPx = 800f, canvasHeightPx = 600f),
        )
        // Check rect's world center maps to screen center after the transform.
        val rectCenterWorld = Position(x = 200f, y = 150f)
        val rectCenterScreen = ViewportTransform.worldToScreen(rectCenterWorld, out)
        assertEquals(400f, rectCenterScreen.x, 0.5f)
        assertEquals(300f, rectCenterScreen.y, 0.5f)
    }
}
