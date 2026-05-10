package com.comfymobile.presentation.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Compose Canvas adapter for the [RenderPlan] data list.
 *
 * Per @Lily PR #19 thread `4da46760` point 2: this is the ONLY place
 * Compose-side code touches the render pipeline. The drawScope walks
 * the pre-built `commands` list once per recomposition and translates
 * each [DrawCommand] into a `drawRect` / `drawPath` / `drawCircle`
 * call. **No look-ups, no descriptor / status / runtime queries
 * happen inside `drawScope`** — the plan was built upstream by
 * [RenderPlanBuilder].
 *
 * T2.1a does not implement gestures or viewport virtualisation;
 * the entire plan paints. T2.1b's pan / zoom / hit-testing layer
 * sits on top of this Composable and feeds a clipped
 * `visibleBounds` back into the plan-builder.
 */
@Composable
fun GraphCanvas(
    plan: RenderPlan,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
) {
    val density = LocalDensity.current
    val dpToPx = remember(density) { with(density) { 1.dp.toPx() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (command in plan.commands) {
                drawCommand(command, dpToPx)
            }
        }
    }
}

/**
 * Single-command translator. Kept private so the only public surface
 * is [GraphCanvas] and the upstream pure-function pipeline.
 */
private fun DrawScope.drawCommand(command: DrawCommand, dpToPx: Float) {
    when (command) {
        is DrawCommand.Edge -> drawEdge(command, dpToPx)
        is DrawCommand.NodeBody -> drawNodeBody(command, dpToPx)
        is DrawCommand.NodeTitle -> drawNodeTitle(command, dpToPx)
        is DrawCommand.NodePort -> drawNodePort(command, dpToPx)
    }
}

private fun DrawScope.drawEdge(edge: DrawCommand.Edge, dpToPx: Float) {
    val path = Path().apply {
        moveTo(edge.path.start.x, edge.path.start.y)
        if (edge.orthogonalFallback) {
            // Drag-time fallback: simple right-angle polyline. T2.1a
            // never emits this; T2.1b's drag handler flips the flag.
            val midX = (edge.path.start.x + edge.path.end.x) / 2f
            lineTo(midX, edge.path.start.y)
            lineTo(midX, edge.path.end.y)
            lineTo(edge.path.end.x, edge.path.end.y)
        } else {
            cubicTo(
                edge.path.control1.x, edge.path.control1.y,
                edge.path.control2.x, edge.path.control2.y,
                edge.path.end.x, edge.path.end.y,
            )
        }
    }
    drawPath(
        path = path,
        color = edge.argb.toComposeColor(),
        style = Stroke(width = edge.widthDp * dpToPx),
    )
}

private fun DrawScope.drawNodeBody(body: DrawCommand.NodeBody, dpToPx: Float) {
    val cornerPx = body.cornerRadiusDp * dpToPx
    val topLeft = Offset(body.rect.x, body.rect.y)
    val size = ComposeSize(body.rect.width, body.rect.height)
    drawRoundRect(
        color = body.fillArgb.toComposeColor(),
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
    )
    drawRoundRect(
        color = body.borderArgb.toComposeColor(),
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
        style = Stroke(width = body.borderWidthDp * dpToPx),
    )
}

private fun DrawScope.drawNodeTitle(title: DrawCommand.NodeTitle, dpToPx: Float) {
    // T2.1a renders the title strip as a tinted rectangle on top of
    // the node body. The actual text + status badge are deferred to
    // a small Compose overlay (see [GraphCanvasWithText]) since
    // drawScope's text handling is not as ergonomic as a Text() call.
    drawRect(
        color = title.titleArgb.toComposeColor(),
        topLeft = Offset(title.rect.x, title.rect.y),
        size = ComposeSize(title.rect.width, title.rect.height),
    )
    title.statusAccentArgb?.let { accent ->
        // Small colour bar at the top of the title strip indicates
        // the node's runtime status (running / done / cached / error)
        // even before icon / spinner overlay lands.
        drawRect(
            color = accent.toComposeColor(),
            topLeft = Offset(title.rect.x, title.rect.y),
            size = ComposeSize(title.rect.width, 3f * dpToPx),
        )
    }
}

private fun DrawScope.drawNodePort(port: DrawCommand.NodePort, dpToPx: Float) {
    drawCircle(
        color = port.argb.toComposeColor(),
        radius = port.radiusDp * dpToPx,
        center = Offset(port.center.x, port.center.y),
    )
}

/**
 * Build a [GraphPalette] from the current Material 3 ColorScheme so
 * the production renderer reflects the active theme (light / dark /
 * dynamic). Tests pass [GraphPalette.defaultLightForTesting] directly.
 */
@Composable
fun rememberGraphPalette(): GraphPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        GraphPalette(
            nodeFillArgb = scheme.surfaceContainer.toArgbLong(),
            nodeFillUnknownArgb = scheme.surfaceContainerHighest.toArgbLong(),
            titleArgb = scheme.surfaceContainerHigh.toArgbLong(),
            titleUnknownArgb = scheme.surfaceVariant.toArgbLong(),
            borderArgb = scheme.outline.toArgbLong(),
            borderSelectedArgb = scheme.primary.toArgbLong(),
            statusRunningArgb = scheme.primary.toArgbLong(),
            statusDoneArgb = scheme.tertiary.toArgbLong(),
            statusCachedArgb = scheme.secondary.toArgbLong(),
            statusErrorArgb = scheme.error.toArgbLong(),
            // Type colours stay fixed regardless of theme — desktop
            // ComfyUI does the same. Per @Ores T2.7 §1.4.
            typeColors = GraphPalette.defaultLightForTesting.typeColors,
            unknownPortArgb = scheme.outline.toArgbLong(),
        )
    }
}

private fun Color.toArgbLong(): Long {
    val a = (alpha * 255f).toInt() and 0xFF
    val r = (red * 255f).toInt() and 0xFF
    val g = (green * 255f).toInt() and 0xFF
    val b = (blue * 255f).toInt() and 0xFF
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()) and
        0xFFFFFFFFL
}

/**
 * ARGB Long (0xAARRGGBB) → Compose [Color]. The lower 32 bits hold
 * the packed ARGB value; truncating to Int and using `Color(Int)`
 * is the canonical Compose conversion.
 */
private fun Long.toComposeColor(): Color = Color(this.toInt())
