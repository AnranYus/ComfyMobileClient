package com.comfymobile.presentation.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Gesture-aware Compose adapter for [GraphCanvas].
 *
 * Per @Lily PR #24 thread constraint #3 (msg `b025c831`) the Compose
 * layer is a thin wiring shim: it translates `pointerInput` events
 * into [GestureIntent] values and dispatches them through [onIntent],
 * which the caller's [GestureReducer.reduce] consumes to produce a
 * fresh [GestureState]. The composable itself is purely declarative
 * — applying the current [gestureState] as a draw-scope transform
 * and as an LOD downgrade flag on the next render.
 *
 * Three pointer input layers stacked:
 *  1. Pan / zoom — `awaitEachGesture` + `calculatePan` / `calculateZoom`
 *     dispatches `GestureIntent.Pan` AND/OR `GestureIntent.Zoom` on
 *     motion (both can fire in the same frame for a pinch+drag —
 *     `Zoom` carries focus-anchor info, `Pan` carries the leftover
 *     centroid translation), framed by `InteractionStart` /
 *     `InteractionEnd`. Per @Lily PR #36 review (`4471956260`) blocker
 *     1: pan must NOT be gated by `!zoomed` or pinch+drag drops the
 *     drag component.
 *  2. Tap / long-press — `detectTapGestures` hit-tests via [HitTester]
 *     and dispatches `Tap(hitNodeId)` / `LongPress(hitNodeId)`.
 *
 * Both pointer-input modifiers coexist via Compose's stacked
 * `pointerInput` blocks (each owns its own gesture detector and
 * doesn't conflict with the others).
 *
 * Per @Ores T2.7 §1.5 / §1.8: edge LOD downgrade is wired by passing
 * `gestureState.isInteracting` into [RenderPlan.Builder.build] as
 * `interactiveLodDowngrade` — the [buildPlan] lambda the caller
 * supplies is responsible for that wiring (see preview).
 *
 * Per @Lily PR #36 review (`4471956260`) blocker 2: this composable
 * owns the `canvasSize` measurement, derives the world-space
 * `visibleBounds` via [ViewportTransform.computeVisibleBounds], and
 * passes that into the caller's [buildPlan] lambda. Callers MUST
 * forward `visibleBounds` to `RenderPlanBuilder.build(...,
 * visibleBounds = ...)` so pan/zoom changes the visible-command set
 * (viewport virtualisation). `visibleBounds` is `null` only on the
 * first frame before `onSizeChanged` fires — callers should treat
 * that case as "build the full plan" (the default
 * `RenderPlanBuilder.visibleBounds = null` already does this).
 */
@Composable
fun InteractiveGraphCanvas(
    layoutResult: LayoutResult,
    gestureState: GestureState,
    onIntent: (GestureIntent) -> Unit,
    buildPlan: (visibleBounds: Rect?) -> RenderPlan,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    overlay: @Composable BoxScope.() -> Unit = { defaultOverlay(onIntent) },
) {
    val density = LocalDensity.current
    val dpToPx = remember(density) { with(density) { 1.dp.toPx() } }
    val measurer = rememberTextMeasurer()

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val visibleBounds: Rect? = remember(canvasSize, gestureState) {
        if (canvasSize == IntSize.Zero) null
        else ViewportTransform.computeVisibleBounds(
            canvasWidthPx = canvasSize.width.toFloat(),
            canvasHeightPx = canvasSize.height.toFloat(),
            gesture = gestureState,
        )
    }
    val plan: RenderPlan = buildPlan(visibleBounds)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onSizeChanged { canvasSize = it }
            // Pan + pinch zoom gesture detector. We don't use
            // `detectTransformGestures` because we need to emit
            // explicit InteractionStart / InteractionEnd intents
            // (the LOD downgrade is driven by gestureState.isInteracting).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var interactionStarted = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointers = event.changes.filter { it.pressed }
                            if (pointers.isEmpty()) break

                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val moved = pointers.any { it.positionChanged() }
                            val zoomed = abs(zoom - 1f) > Z_DEAD_ZONE
                            val panned = moved && pan != Offset.Zero

                            if (!interactionStarted && (moved || zoomed)) {
                                onIntent(GestureIntent.InteractionStart)
                                interactionStarted = true
                            }

                            // Zoom first so its focus-anchor pan adjustment
                            // happens before any leftover centroid translation
                            // is layered on by Pan. Reducer composes pan via
                            // delta-addition so the order is safe.
                            if (zoomed) {
                                val focus = pointers.fold(Offset.Zero) { acc, p -> acc + p.position } /
                                    pointers.size.toFloat()
                                onIntent(
                                    GestureIntent.Zoom(
                                        factor = zoom,
                                        focusScreenX = focus.x,
                                        focusScreenY = focus.y,
                                    )
                                )
                            }
                            // Per @Lily PR #36 review (`4471956260`) blocker 1:
                            // emit Pan regardless of zoom — pinch + drag must
                            // not drop the centroid translation component.
                            if (panned) {
                                onIntent(GestureIntent.Pan(pan.x, pan.y))
                            }
                            if (zoomed || panned) {
                                pointers.forEach { it.consume() }
                            }
                        } while (pointers.any { it.pressed })
                    } finally {
                        if (interactionStarted) onIntent(GestureIntent.InteractionEnd)
                    }
                }
            }
            // Separate pointerInput for taps so the gesture detector
            // above (which consumes pointer events on actual motion)
            // doesn't swallow stationary taps.
            .pointerInput(layoutResult, gestureState) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = HitTester.hitTest(
                            screenX = offset.x,
                            screenY = offset.y,
                            layout = layoutResult,
                            gesture = gestureState,
                        )
                        onIntent(
                            GestureIntent.Tap(
                                screenX = offset.x,
                                screenY = offset.y,
                                hitNodeId = hit?.nodeId,
                            )
                        )
                    },
                    onLongPress = { offset ->
                        val hit = HitTester.hitTest(
                            screenX = offset.x,
                            screenY = offset.y,
                            layout = layoutResult,
                            gesture = gestureState,
                        )
                        onIntent(
                            GestureIntent.LongPress(
                                screenX = offset.x,
                                screenY = offset.y,
                                hitNodeId = hit?.nodeId,
                            )
                        )
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            translate(left = gestureState.panX * gestureState.zoomScale, top = gestureState.panY * gestureState.zoomScale) {
                scale(
                    scaleX = gestureState.zoomScale,
                    scaleY = gestureState.zoomScale,
                    pivot = Offset.Zero,
                ) {
                    for (command in plan.commands) {
                        drawCommand(command, dpToPx, measurer)
                    }
                }
            }
        }
        overlay()
    }
}

/** Receiver alias so the overlay callback signature reads cleanly. */
typealias BoxScope = androidx.compose.foundation.layout.BoxScope

/**
 * Default overlay (canvas controls in the top-right corner, per
 * @Ores T2.7 §1.7): reset view + fit-to-selection buttons.
 *
 * Fit-to-selection requires the caller to supply layoutResult bounds;
 * here we dispatch the [GestureIntent.ResetView] only. Hosts that
 * support fit-to-selection wire a custom overlay that computes the
 * selected-node-plus-neighbors rect and dispatches
 * [GestureIntent.FitTo] with it.
 */
@Composable
private fun BoxScope.defaultOverlay(onIntent: (GestureIntent) -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            IconButton(
                onClick = { onIntent(GestureIntent.ResetView) },
                modifier = Modifier.size(48.dp),
            ) {
                // Glyph-free placeholder text; T2.1b spec calls for an
                // icon but the cross-platform icon pack isn't wired here.
                // A future T2.1b-followup PR can swap to a real icon.
                Text("⟳", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private const val Z_DEAD_ZONE = 0.001f
