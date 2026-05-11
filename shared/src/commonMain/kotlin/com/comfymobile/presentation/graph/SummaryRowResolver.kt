package com.comfymobile.presentation.graph

import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.NodeDescriptor
import com.comfymobile.domain.node.ParamDescriptor
import com.comfymobile.domain.workflow.WorkflowWidgetValueIndex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One summary line rendered inside a node body when the descriptor is
 * known. Pre-formatted text — the render layer just lays it out and
 * draws it; no JSON / descriptor lookups happen inside `drawScope`.
 *
 * Per @Ores T2.7 §1.3 (clarified in PR #21 thread msg `6b943636`):
 * summary rows are *mechanical* — they show
 * `descriptor.editableParams.take(3)` in declaration order, plus a
 * trailing `…N more` line when there are more than 4 params (so the
 * user sees a "there's more" affordance without overcrowding the
 * card).
 *
 * @property text The formatted line ready to draw, e.g.
 *   `"steps: 30"`, `"sampler: euler_a"`, `"text: a beauti…"`,
 *   `"…3 more"`.
 * @property emphasis Hints the renderer about visual weight. Most
 *   rows are [Emphasis.PARAM]; the trailing "…N more" line is
 *   [Emphasis.MORE_HINT] so the renderer can draw it dimmer / italic.
 */
data class SummaryEntry(
    val text: String,
    val emphasis: Emphasis = Emphasis.PARAM,
) {
    enum class Emphasis { PARAM, MORE_HINT }
}

/**
 * Pure-function helper that produces a node's summary rows from its
 * [ParsedNode.widgetsValues] + the matching [NodeDescriptor]
 * editable-param list. Lives outside [RenderPlanBuilder] so the
 * formatting rules can be unit-tested independently of layout +
 * draw-command generation.
 *
 * Per @Ores PR #21 thread `6b943636`: takes the first 3 params,
 * appends a `…N more` line when more than 4 exist. Per-control
 * compact rendering rules per T2.7 §1.3.
 */
object SummaryRowResolver {

    /** Maximum visible param rows before we switch to the "more hint" form. */
    const val MAX_VISIBLE_ROWS: Int = 3

    /** Truncation cap for free-text values to keep card height bounded. */
    private const val TEXT_TRUNCATE: Int = 18

    /**
     * @return Empty list when the node is unknown (no descriptor) —
     *   unknown nodes collapse to title-only per @Ores §1.1; callers
     *   never ask for summary rows on TITLE_ONLY bodies.
     */
    fun resolve(node: ParsedNode, descriptor: NodeDescriptor?): List<SummaryEntry> {
        if (descriptor == null) return emptyList()
        val params = descriptor.editableParams
        if (params.isEmpty()) return emptyList()

        val rows = params.take(MAX_VISIBLE_ROWS).map { param ->
            val widgetIndex = WorkflowWidgetValueIndex.indexOf(
                classType = node.classType,
                descriptor = descriptor,
                param = param,
            )
            SummaryEntry(
                text = formatParamRow(
                    param = param,
                    valueAtSlot = widgetIndex?.let { node.widgetsValues.getOrNull(it) },
                ),
                emphasis = SummaryEntry.Emphasis.PARAM,
            )
        }
        val total = params.size
        // "…N more" appears only when there are MORE than MAX_VISIBLE_ROWS + 1
        // params (i.e. total > 4). At exactly MAX_VISIBLE_ROWS + 1 we
        // could also show the 4th param directly; per @Ores spec the
        // cutoff is 4 → start collapsing.
        return if (total > MAX_VISIBLE_ROWS + 1) {
            rows + SummaryEntry(
                text = "…${total - MAX_VISIBLE_ROWS} more",
                emphasis = SummaryEntry.Emphasis.MORE_HINT,
            )
        } else {
            rows
        }
    }

    /**
     * Format `param: value` for a single row. Pure function — no
     * locale-dependent number formatting (uses kotlin defaults), so
     * snapshots are deterministic across platforms.
     */
    private fun formatParamRow(param: ParamDescriptor, valueAtSlot: JsonElement?): String {
        val name = compactParamLabel(param)
        val valueText = formatValue(param.control, valueAtSlot)
        return "$name: $valueText"
    }

    /**
     * Per @Ores §1.3 compact rendering: use the param `name` (e.g.
     * `seed`, `steps`, `cfg`), NOT the localised `displayName` — to
     * keep summary rows narrow and to match what desktop ComfyUI users
     * see in their widgets. The localised name appears in the editor
     * drawer (T2.2), not on the graph card.
     */
    private fun compactParamLabel(param: ParamDescriptor): String = param.name

    /**
     * Format the value side of a row according to the param's
     * [ControlType]. Each branch is intentionally short so the formatting
     * stays predictable for snapshots.
     */
    private fun formatValue(control: ControlType, value: JsonElement?): String {
        if (value == null || value is JsonNull) return "—"
        return when (control) {
            is ControlType.Slider -> formatNumberWithStep(value, step = control.step)
            ControlType.Number -> formatNumeric(value)
            is ControlType.Integer -> formatInteger(value)
            ControlType.Toggle -> formatBoolean(value)
            ControlType.SingleLineText -> formatString(value, truncate = TEXT_TRUNCATE)
            is ControlType.MultilineText -> formatString(value, truncate = TEXT_TRUNCATE)
            is ControlType.Dropdown -> formatString(value, truncate = TEXT_TRUNCATE)
            is ControlType.ModelPicker -> formatString(value, truncate = TEXT_TRUNCATE)
            is ControlType.ImagePicker -> formatString(value, truncate = TEXT_TRUNCATE)
            ControlType.Hidden -> "(hidden)"
        }
    }

    // ---------------------------------------------------------------- formatters

    private fun formatNumberWithStep(value: JsonElement, step: Double): String {
        val raw = (value as? JsonPrimitive)?.doubleOrNull
            ?: (value as? JsonPrimitive)?.longOrNull?.toDouble()
            ?: return value.toString()
        // Step decides decimal precision: integer step → no decimals;
        // step 0.1 → 1 decimal; step 0.01 → 2 decimals; ... cap at 3.
        val decimals = when {
            step >= 1.0 -> 0
            step >= 0.1 -> 1
            step >= 0.01 -> 2
            else -> 3
        }
        return raw.formatDecimals(decimals)
    }

    private fun formatNumeric(value: JsonElement): String =
        (value as? JsonPrimitive)?.doubleOrNull?.formatDecimals(2)
            ?: (value as? JsonPrimitive)?.longOrNull?.toString()
            ?: value.toString()

    private fun formatInteger(value: JsonElement): String =
        (value as? JsonPrimitive)?.longOrNull?.toString()
            ?: (value as? JsonPrimitive)?.doubleOrNull?.toLong()?.toString()
            ?: value.toString()

    private fun formatBoolean(value: JsonElement): String =
        (value as? JsonPrimitive)?.booleanOrNull?.let { if (it) "on" else "off" }
            ?: value.toString()

    private fun formatString(value: JsonElement, truncate: Int): String {
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: value.toString()
        return if (raw.length > truncate) raw.take(truncate - 1) + "…" else raw
    }

    /**
     * Locale-free decimal formatting. Compose / Compose Multiplatform
     * doesn't expose a portable `printf`-like API in commonMain, so we
     * roll our own simple fixed-point formatter — sufficient for the
     * ranges seen in v1 descriptors (0..2048 wide, up to 3 decimals).
     */
    private fun Double.formatDecimals(decimals: Int): String {
        if (decimals <= 0) return this.toLong().toString()
        val pow = listOf(1.0, 10.0, 100.0, 1000.0)[decimals.coerceIn(0, 3)]
        val rounded = kotlin.math.round(this * pow) / pow
        val intPart = rounded.toLong()
        val frac = kotlin.math.abs((rounded - intPart) * pow).toLong()
        val sign = if (rounded < 0 && intPart == 0L) "-" else ""
        val fracStr = frac.toString().padStart(decimals, '0')
        return "$sign$intPart.$fracStr"
    }
}
