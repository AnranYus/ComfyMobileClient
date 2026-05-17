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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose Canvas adapter for the [RenderPlan] data list.
 *
 * Per @Lily PR #19 thread `4da46760` point 2: this is the ONLY place
 * Compose-side code touches the render pipeline. The drawScope walks
 * the pre-built `commands` list once per recomposition and translates
 * each [DrawCommand] into a `drawRect` / `drawPath` / `drawCircle` /
 * `drawText` call. **No look-ups, no descriptor / status / runtime
 * queries happen inside `drawScope`** — the plan was built upstream
 * by [RenderPlanBuilder].
 *
 * Text rendering uses [TextMeasurer] (Compose 1.6+) so titles +
 * summary rows are pre-laid-out before drawScope runs — this keeps
 * Lily's "no lookups inside drawScope" contract intact even for
 * text.
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
    val measurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (command in plan.commands) {
                drawCommand(command, dpToPx, measurer)
            }
        }
    }
}

/**
 * Single-command translator. Was private until T2.1b's
 * [InteractiveGraphCanvas] needed to share the drawCommand fan-out
 * inside a `withTransform` block.
 */
internal fun DrawScope.drawCommand(
    command: DrawCommand,
    dpToPx: Float,
    measurer: TextMeasurer,
) {
    when (command) {
        is DrawCommand.Edge -> drawEdge(command, dpToPx)
        is DrawCommand.NodeBody -> drawNodeBody(command, dpToPx)
        is DrawCommand.NodeTitle -> drawNodeTitle(command, dpToPx, measurer)
        is DrawCommand.NodePort -> drawNodePort(command, dpToPx)
        is DrawCommand.SummaryRow -> drawSummaryRow(command, dpToPx, measurer)
    }
}

private fun DrawScope.drawEdge(edge: DrawCommand.Edge, dpToPx: Float) {
    val path = Path().apply {
        moveTo(edge.path.start.x, edge.path.start.y)
        if (edge.straightLineFallback) {
            // Drag/pan/zoom-time fallback: straight line per @Ores
            // PR #21 §1.5/§1.8 locked LOD contract. T2.1a never emits
            // this; T2.1b's gesture handler toggles `straightLineFallback`
            // during interaction.
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

private fun DrawScope.drawNodeTitle(
    title: DrawCommand.NodeTitle,
    dpToPx: Float,
    measurer: TextMeasurer,
) {
    // Title strip background
    drawRect(
        color = title.titleArgb.toComposeColor(),
        topLeft = Offset(title.rect.x, title.rect.y),
        size = ComposeSize(title.rect.width, title.rect.height),
    )
    // Status accent bar (3dp strip at the top of the title)
    title.statusAccentArgb?.let { accent ->
        drawRect(
            color = accent.toComposeColor(),
            topLeft = Offset(title.rect.x, title.rect.y),
            size = ComposeSize(title.rect.width, 3f * dpToPx),
        )
    }
    // Title text — laid out by TextMeasurer so glyph metrics are
    // computed off the drawScope. Unknown nodes render italic per
    // @Ores T2.7 §1.1 (TITLE_ONLY collapse cue).
    val textPaddingPx = 8f * dpToPx
    val style = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontStyle = if (title.italic) FontStyle.Italic else FontStyle.Normal,
        // Title text colour derived from the title fill for legibility.
        // Production palette would compute a proper onSurface tone;
        // for T2.1a a fixed dark colour is fine since title fills are
        // light (Material 3 surfaceContainerHigh / surfaceVariant).
        color = Color(0xFF212121),
    )
    // Reserve roughly 28dp of right-side space for the (future)
    // status badge / icon so text doesn't run under it.
    val statusReservePx = 28f * dpToPx
    // Per @Lily PR #24 review (`246d52a8`) blocker 2: constrain
    // title text to the available width so long class_type strings
    // ellipsis instead of overflowing into the badge / outside the
    // node card. The available width is the title strip minus left
    // padding (textPaddingPx) and the right-side status reservation.
    val titleAvailableWidthPx =
        (title.rect.width - textPaddingPx - statusReservePx)
            .coerceAtLeast(1f)
            .toInt()
    val measured = measurer.measure(
        text = title.text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = titleAvailableWidthPx),
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            x = title.rect.x + textPaddingPx,
            y = title.rect.y + (title.rect.height - measured.size.height) / 2f,
        ),
    )
    // status badge glyph
    val badgeGlyph = when (title.statusBadge) {
        StatusBadge.NONE -> null
        StatusBadge.SPINNER -> "◌"
        StatusBadge.DONE -> "✓"
        StatusBadge.CACHED -> "⚡"
        StatusBadge.ERROR -> "⚠"
    }
    if (badgeGlyph != null) {
        val badgeArgb = title.statusAccentArgb ?: 0xFF424242L
        val badgeStyle = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = badgeArgb.toComposeColor(),
        )
        val badgeMeasured = measurer.measure(text = badgeGlyph, style = badgeStyle, maxLines = 1)
        drawText(
            textLayoutResult = badgeMeasured,
            topLeft = Offset(
                x = title.rect.x + title.rect.width - statusReservePx + (statusReservePx - badgeMeasured.size.width) / 2f,
                y = title.rect.y + (title.rect.height - badgeMeasured.size.height) / 2f,
            ),
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

private fun DrawScope.drawSummaryRow(
    row: DrawCommand.SummaryRow,
    dpToPx: Float,
    measurer: TextMeasurer,
) {
    val style = TextStyle(
        fontSize = row.fontSizeSp.sp,
        fontStyle = if (row.emphasis == SummaryEntry.Emphasis.MORE_HINT) {
            FontStyle.Italic
        } else {
            FontStyle.Normal
        },
        color = row.textArgb.toComposeColor(),
    )
    // Per @Lily PR #24 review (`246d52a8`) blocker 2: constrain text
    // measurement to the node body's available width so long values
    // ellipsis cleanly instead of overflowing the card.
    val maxWidthInt = row.maxWidthPx.coerceAtLeast(1f).toInt()
    val measured = measurer.measure(
        text = row.text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidthInt),
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(row.origin.x, row.origin.y),
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
            // ⚡ cached → tertiaryContainer (Material 3's standard
            // "low-saturation tertiary") per @Ores PR #24 thread
            // msg `7bc86ac5`: the cached node is "quietly complete",
            // it shouldn't compete visually with the active ✓ done
            // state.
            statusCachedArgb = scheme.tertiaryContainer.toArgbLong(),
            statusErrorArgb = scheme.error.toArgbLong(),
            // Type colours stay fixed regardless of theme — desktop
            // ComfyUI does the same. Per @Ores T2.7 §1.4.
            typeColors = GraphPalette.defaultLightForTesting.typeColors,
            unknownPortArgb = scheme.outline.toArgbLong(),
        )
    }
}

/**
 * Theme-derived summary-row palette matching the active Material 3
 * ColorScheme. See class docs on [SummaryRowPalette].
 */
@Composable
fun rememberSummaryRowPalette(): SummaryRowPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        SummaryRowPalette(
            paramTextArgb = scheme.onSurfaceVariant.toArgbLong(),
            moreHintTextArgb = scheme.outline.toArgbLong(),
            fontSizeSp = 11f,
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
