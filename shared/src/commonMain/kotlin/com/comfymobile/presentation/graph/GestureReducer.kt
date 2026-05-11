package com.comfymobile.presentation.graph

/**
 * One discrete intent translated from a Compose `pointerInput` event.
 *
 * Per @Lily PR #24 thread review constraint #3 (msg `b025c831`): the
 * Compose layer stays a thin wiring shim — it converts platform
 * pointer events into [GestureIntent] values and dispatches them
 * through [GestureReducer.reduce]. All state-transition logic lives
 * here as a pure function so it can be unit-tested without spinning
 * up a UI.
 *
 * Mirrors the same pattern as [com.comfymobile.data.network.ConnectionStateReducer]:
 * pure data in, pure data out.
 */
sealed interface GestureIntent {

    /**
     * Pan delta in **screen-space pixels** (Compose `pointerInput`
     * pan delta). Reducer converts to world-space using the current
     * zoom scale.
     */
    data class Pan(val deltaScreenX: Float, val deltaScreenY: Float) : GestureIntent

    /**
     * Pinch zoom factor (multiplicative). Compose's
     * `detectTransformGestures` provides this as `zoomChange` in
     * each onGesture callback; >1.0 = zoom in, <1.0 = zoom out.
     *
     * @property focusScreenX Screen-space x of the pinch focus point
     *   (typically the midpoint of the two touch points). Reducer
     *   adjusts pan so the focus stays anchored.
     * @property focusScreenY Same for y.
     */
    data class Zoom(
        val factor: Float,
        val focusScreenX: Float,
        val focusScreenY: Float,
    ) : GestureIntent

    /**
     * Single-finger tap at a screen-space point. Selects a node if a
     * hit is found, clears selection otherwise. [HitTester.hitTest]
     * is consulted by the caller before dispatching.
     */
    data class Tap(val screenX: Float, val screenY: Float, val hitNodeId: String?) : GestureIntent

    /**
     * Long-press at a screen-space point. Same hit-test pre-resolve
     * as [Tap]; the UI layer additionally triggers the param drawer
     * (T2.2) on long-press hits.
     */
    data class LongPress(val screenX: Float, val screenY: Float, val hitNodeId: String?) : GestureIntent

    /**
     * Pointer-down begin: marks an interaction as active (drives LOD
     * downgrade per @Ores PR #21 §1.5/§1.8 locked contract). Emitted
     * when the user starts a pan or zoom gesture.
     */
    data object InteractionStart : GestureIntent

    /**
     * Pointer-up settle: clears [GestureState.isInteracting] so the
     * renderer switches back to bezier edges. Emitted on gesture
     * completion (all pointers up).
     */
    data object InteractionEnd : GestureIntent

    /**
     * User-pressed "reset view" overlay button (per @Ores T2.7
     * §1.7). Resets pan to 0, zoom to 1, keeps selection.
     */
    data object ResetView : GestureIntent

    /**
     * User-pressed "fit to selection" overlay button (per @Ores T2.7
     * §1.7). The UI provides the selected node's world rect; reducer
     * recenters and rescales the viewport to fit that rect plus
     * [FitToSelectionPadding] world units of margin.
     *
     * If the rect is empty (no selection) the intent is a no-op.
     */
    data class FitTo(
        val worldRect: Rect,
        val canvasWidthPx: Float,
        val canvasHeightPx: Float,
    ) : GestureIntent

    companion object {
        /** World-space padding around the rect when fitting to selection. */
        const val FitToSelectionPadding: Float = 60f
    }
}

/**
 * Pure-function reducer: `(GestureState, GestureIntent) → GestureState`.
 *
 * Stateless: all transition logic is here, no field reads outside
 * the function arguments. Tests can drive the full state space
 * deterministically.
 *
 * Per @Lily PR #24 thread review #3: this is the seam that makes
 * pan/zoom/tap/long-press not eat each other's events — Compose
 * never branches on gesture type internally, it just dispatches
 * intents.
 */
object GestureReducer {

    fun reduce(state: GestureState, intent: GestureIntent): GestureState = when (intent) {
        is GestureIntent.Pan -> reducePan(state, intent)
        is GestureIntent.Zoom -> reduceZoom(state, intent)
        is GestureIntent.Tap -> state.copy(selectedNodeId = intent.hitNodeId)
        is GestureIntent.LongPress -> state.copy(selectedNodeId = intent.hitNodeId)
        GestureIntent.InteractionStart -> state.copy(isInteracting = true)
        GestureIntent.InteractionEnd -> state.copy(isInteracting = false)
        GestureIntent.ResetView -> state.copy(panX = 0f, panY = 0f, zoomScale = 1f)
        is GestureIntent.FitTo -> reduceFitTo(state, intent)
    }

    // ---------------------------------------------------------------- transforms

    /**
     * Convert a screen-space pan delta into a world-space pan
     * adjustment. Higher zoom → smaller world-space pan for the
     * same screen-pixel drag.
     */
    private fun reducePan(state: GestureState, intent: GestureIntent.Pan): GestureState {
        // Screen delta divided by zoom scale = world delta the
        // camera moved. We move the world the opposite direction
        // (camera right = world left) but the convention in this
        // codebase has panX = world offset, so a positive screen-x
        // drag (drag right) moves the world right under the camera,
        // i.e. panX increases.
        val zoom = state.zoomScale.coerceAtLeast(0.0001f)
        return state.copy(
            panX = state.panX + intent.deltaScreenX / zoom,
            panY = state.panY + intent.deltaScreenY / zoom,
        )
    }

    /**
     * Apply zoom factor with focus-point anchoring: the world point
     * under the screen-space focus before the zoom stays under it
     * after. This is the natural pinch-zoom expectation.
     */
    private fun reduceZoom(state: GestureState, intent: GestureIntent.Zoom): GestureState {
        val newZoomUnclamped = state.zoomScale * intent.factor
        val newZoom = newZoomUnclamped.coerceIn(GestureState.ZOOM_MIN, GestureState.ZOOM_MAX)
        if (newZoom == state.zoomScale) return state // already at limit
        // World point under the focus before zoom:
        //   worldX = (focusScreenX - panX * oldZoom) / oldZoom + panX  (simplified)
        //   = focusScreenX / oldZoom - panX + panX = focusScreenX / oldZoom
        //   ... no, our convention is panX is in WORLD units, so:
        //   worldX_at_focus_before = focusScreenX / oldZoom + (-panX) ... actually let's derive it cleanly.
        //
        // worldToScreen: screen = (world + pan) * zoom  (per ViewportTransform)
        // screenToWorld: world  = screen / zoom - pan
        //
        // Want: worldUnderFocus_before = worldUnderFocus_after
        //   focusX / oldZoom - oldPanX = focusX / newZoom - newPanX
        //   newPanX = focusX / newZoom - focusX / oldZoom + oldPanX
        //   newPanX = focusX * (1/newZoom - 1/oldZoom) + oldPanX
        val newPanX = state.panX + intent.focusScreenX * (1f / newZoom - 1f / state.zoomScale)
        val newPanY = state.panY + intent.focusScreenY * (1f / newZoom - 1f / state.zoomScale)
        return state.copy(
            panX = newPanX,
            panY = newPanY,
            zoomScale = newZoom,
        )
    }

    private fun reduceFitTo(state: GestureState, intent: GestureIntent.FitTo): GestureState {
        if (intent.worldRect.isEmpty) return state
        val padding = GestureIntent.FitToSelectionPadding
        val rectW = (intent.worldRect.right - intent.worldRect.left) + padding * 2f
        val rectH = (intent.worldRect.bottom - intent.worldRect.top) + padding * 2f
        if (rectW <= 0f || rectH <= 0f) return state
        // Zoom = min(canvas / rect dimension) so the rect fits inside
        // the canvas without stretching.
        val zoomFitX = intent.canvasWidthPx / rectW
        val zoomFitY = intent.canvasHeightPx / rectH
        val newZoom = kotlin.math.min(zoomFitX, zoomFitY)
            .coerceIn(GestureState.ZOOM_MIN, GestureState.ZOOM_MAX)
        // Center the rect on the canvas: place the rect's center at
        // (canvas/2). Solve for pan:
        //   centerScreenX = (rect.centerX + pan.x) * zoom
        //   pan.x = centerScreenX / zoom - rect.centerX
        val rectCenterX = (intent.worldRect.left + intent.worldRect.right) / 2f
        val rectCenterY = (intent.worldRect.top + intent.worldRect.bottom) / 2f
        val newPanX = (intent.canvasWidthPx / 2f) / newZoom - rectCenterX
        val newPanY = (intent.canvasHeightPx / 2f) / newZoom - rectCenterY
        return state.copy(
            panX = newPanX,
            panY = newPanY,
            zoomScale = newZoom,
        )
    }
}
