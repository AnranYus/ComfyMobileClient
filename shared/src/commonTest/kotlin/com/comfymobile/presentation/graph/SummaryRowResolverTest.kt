package com.comfymobile.presentation.graph

import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.LocalizedString
import com.comfymobile.domain.node.NodeDescriptor
import com.comfymobile.domain.node.ParamDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the @Ores T2.7 §1.3 mechanical rule for summary rows:
 *  - take(3) from `descriptor.editableParams` in declaration order
 *  - if total > 4, append "…N more" as a MORE_HINT row
 *  - per-control formatting compact
 *
 * Pure-data — no Compose, no IO. Snapshots are deterministic across
 * platforms (no locale-dependent formatting).
 */
class SummaryRowResolverTest {

    private fun param(name: String, control: ControlType): ParamDescriptor =
        ParamDescriptor(
            name = name,
            displayName = LocalizedString(zh = name, en = name),
            control = control,
        )

    private fun descriptor(vararg params: ParamDescriptor): NodeDescriptor =
        NodeDescriptor(
            classType = "Stub",
            displayName = LocalizedString(zh = "Stub", en = "Stub"),
            category = "test",
            editableParams = params.toList(),
        )

    private fun node(values: List<JsonElement>): ParsedNode = ParsedNode(
        id = "n",
        classType = "Stub",
        widgetsValues = values,
    )

    // ---------------------------------------------------------------- presence / counts

    @Test fun unknown_node_yields_empty_summary() {
        val rows = SummaryRowResolver.resolve(node(emptyList()), descriptor = null)
        assertEquals(emptyList<SummaryEntry>(), rows)
    }

    @Test fun descriptor_with_no_params_yields_empty_summary() {
        val rows = SummaryRowResolver.resolve(node(emptyList()), descriptor())
        assertEquals(emptyList<SummaryEntry>(), rows)
    }

    @Test fun three_or_fewer_params_render_all_without_more_hint() {
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(10), JsonPrimitive(20), JsonPrimitive(30))),
            descriptor = descriptor(
                param("a", ControlType.Integer()),
                param("b", ControlType.Integer()),
                param("c", ControlType.Integer()),
            ),
        )
        assertEquals(3, rows.size)
        assertEquals("a: 10", rows[0].text)
        assertEquals("b: 20", rows[1].text)
        assertEquals("c: 30", rows[2].text)
        assertTrue(rows.none { it.emphasis == SummaryEntry.Emphasis.MORE_HINT })
    }

    @Test fun four_params_render_all_three_plus_no_more_hint() {
        // The @Ores cutoff is "more than 4 → collapse". At exactly 4
        // we could show all 4 but spec keeps the 3-row cap with no
        // "…N more" line (since hint would say "…1 more" and look odd).
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3), JsonPrimitive(4))),
            descriptor = descriptor(
                param("a", ControlType.Integer()),
                param("b", ControlType.Integer()),
                param("c", ControlType.Integer()),
                param("d", ControlType.Integer()),
            ),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.emphasis == SummaryEntry.Emphasis.MORE_HINT })
    }

    @Test fun five_or_more_params_emit_three_param_rows_plus_more_hint() {
        // KSampler-like: 6 params → "seed: ... / steps: ... / cfg: ... / …3 more"
        val rows = SummaryRowResolver.resolve(
            node = node(
                listOf(
                    JsonPrimitive(12345),
                    JsonPrimitive(30),
                    JsonPrimitive(7.5),
                    JsonPrimitive("euler_a"),
                    JsonPrimitive("normal"),
                    JsonPrimitive(1.0),
                ),
            ),
            descriptor = descriptor(
                param("seed", ControlType.Integer()),
                param("steps", ControlType.Integer(min = 1, max = 200)),
                param("cfg", ControlType.Slider(min = 0.0, max = 30.0, step = 0.5)),
                param("sampler_name", ControlType.Dropdown(source = com.comfymobile.domain.node.Source.NodeEnumFromObjectInfo())),
                param("scheduler", ControlType.Dropdown(source = com.comfymobile.domain.node.Source.NodeEnumFromObjectInfo())),
                param("denoise", ControlType.Slider(min = 0.0, max = 1.0, step = 0.01)),
            ),
        )
        assertEquals(4, rows.size)
        assertEquals(SummaryEntry.Emphasis.PARAM, rows[0].emphasis)
        assertEquals(SummaryEntry.Emphasis.PARAM, rows[1].emphasis)
        assertEquals(SummaryEntry.Emphasis.PARAM, rows[2].emphasis)
        assertEquals(SummaryEntry.Emphasis.MORE_HINT, rows[3].emphasis)
        // The hint shows the remaining count (6 total - 3 shown = 3 more).
        assertEquals("…3 more", rows[3].text)
    }

    // ---------------------------------------------------------------- formatting per control type

    @Test fun integer_value_renders_without_decimals() {
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(42))),
            descriptor = descriptor(param("steps", ControlType.Integer(min = 1, max = 200))),
        )
        assertEquals("steps: 42", rows.single().text)
    }

    @Test fun slider_value_decimals_follow_step() {
        // step=0.5 → 1 decimal
        val rows1 = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(7.5))),
            descriptor = descriptor(param("cfg", ControlType.Slider(min = 0.0, max = 30.0, step = 0.5))),
        )
        assertEquals("cfg: 7.5", rows1.single().text)
        // step=0.01 → 2 decimals
        val rows2 = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(0.7))),
            descriptor = descriptor(param("denoise", ControlType.Slider(min = 0.0, max = 1.0, step = 0.01))),
        )
        assertEquals("denoise: 0.70", rows2.single().text)
        // step=1 → no decimals
        val rows3 = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(20))),
            descriptor = descriptor(param("width", ControlType.Slider(min = 64.0, max = 2048.0, step = 64.0))),
        )
        assertEquals("width: 20", rows3.single().text)
    }

    @Test fun multiline_text_value_truncates_with_ellipsis() {
        val long = "a beautiful landscape with mountains and a lake at sunset"
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(long))),
            descriptor = descriptor(
                param("text", ControlType.MultilineText()),
            ),
        )
        val text = rows.single().text
        assertTrue(text.startsWith("text: a beautiful "), "expected truncated prefix, got: $text")
        assertTrue(text.endsWith("…"), "expected ellipsis suffix, got: $text")
        // Total chars in label "text: " (6) + truncated content
        // (TEXT_TRUNCATE = 18). The truncated portion is 17 chars + …
        assertTrue(text.length <= 6 + 18, "row too long: ${text.length} chars: $text")
    }

    @Test fun dropdown_value_renders_string_truncated() {
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive("dpmpp_2m_sde_karras"))),
            descriptor = descriptor(
                param("sampler", ControlType.Dropdown(source = com.comfymobile.domain.node.Source.NodeEnumFromObjectInfo())),
            ),
        )
        // "dpmpp_2m_sde_karras" is 19 chars; TEXT_TRUNCATE=18 → truncated
        assertTrue(rows.single().text.startsWith("sampler: "), "got: ${rows.single().text}")
        assertTrue(rows.single().text.endsWith("…"), "got: ${rows.single().text}")
    }

    @Test fun toggle_value_renders_on_or_off() {
        val rowsOn = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(true))),
            descriptor = descriptor(param("enabled", ControlType.Toggle)),
        )
        assertEquals("enabled: on", rowsOn.single().text)
        val rowsOff = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(false))),
            descriptor = descriptor(param("enabled", ControlType.Toggle)),
        )
        assertEquals("enabled: off", rowsOff.single().text)
    }

    @Test fun hidden_value_renders_placeholder_not_actual_value() {
        // Hidden params shouldn't appear in editableParams in v1, but
        // defensively if they do, the renderer says "(hidden)" not the
        // raw value.
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive("secret-data"))),
            descriptor = descriptor(param("internal", ControlType.Hidden)),
        )
        assertEquals("internal: (hidden)", rows.single().text)
    }

    @Test fun missing_widgets_value_renders_em_dash() {
        // descriptor has 2 params but widgets_values has only 1 → second slot is null
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonPrimitive(10))),
            descriptor = descriptor(
                param("a", ControlType.Integer()),
                param("b", ControlType.Integer()),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals("a: 10", rows[0].text)
        assertEquals("b: —", rows[1].text)
    }

    @Test fun null_widgets_value_renders_em_dash() {
        val rows = SummaryRowResolver.resolve(
            node = node(listOf(JsonNull)),
            descriptor = descriptor(param("x", ControlType.Integer())),
        )
        assertEquals("x: —", rows.single().text)
    }
}
