package com.comfymobile.presentation.graph

import com.comfymobile.domain.node.LocalizedString
import com.comfymobile.domain.node.NodeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the per-node + per-port style contracts that
 * [com.comfymobile.presentation.graph.NodeStyleResolver] hands the
 * Compose Canvas. Pure-data tests — no UI scaffold, no Compose
 * dependency.
 *
 * Per @Lily PR #19 thread `4da46760` point 2 + @Ores T2.7 §1.4 lock:
 * type colours come from the desktop ComfyUI palette so users coming
 * from the web client identify connections by colour at a glance.
 */
class NodeStyleResolverTest {

    private val palette = GraphPalette.defaultLightForTesting

    private fun descriptor(classType: String): NodeDescriptor = NodeDescriptor(
        classType = classType,
        displayName = LocalizedString(zh = classType, en = classType),
        category = "test",
    )

    private fun node(classType: String): ParsedNode = ParsedNode(
        id = "n",
        classType = classType,
    )

    // ---------------------------------------------------------------- node body

    @Test fun whitelisted_node_renders_full_body() {
        val style = NodeStyleResolver.resolve(
            node = node("KSampler"),
            descriptor = descriptor("KSampler"),
            runtimeStatus = NodeRuntimeStatus.IDLE,
            palette = palette,
        )
        assertEquals(BodyMode.FULL, style.bodyMode)
        assertEquals(palette.nodeFillArgb, style.fillArgb)
        assertEquals(palette.titleArgb, style.titleArgb)
    }

    @Test fun unknown_node_collapses_to_title_only_with_unknown_palette() {
        val style = NodeStyleResolver.resolve(
            node = node("CustomMysteryNode"),
            descriptor = null, // not in registry
            runtimeStatus = NodeRuntimeStatus.IDLE,
            palette = palette,
        )
        assertEquals(BodyMode.TITLE_ONLY, style.bodyMode)
        assertEquals(palette.nodeFillUnknownArgb, style.fillArgb)
        assertEquals(palette.titleUnknownArgb, style.titleArgb)
    }

    // ---------------------------------------------------------------- status badges

    @Test fun runtime_status_idle_yields_no_badge() {
        val style = NodeStyleResolver.resolve(
            node = node("KSampler"),
            descriptor = descriptor("KSampler"),
            runtimeStatus = NodeRuntimeStatus.IDLE,
            palette = palette,
        )
        assertEquals(StatusBadge.NONE, style.statusBadge)
        assertNull(NodeStyleResolver.statusAccentArgb(NodeRuntimeStatus.IDLE, palette))
    }

    @Test fun running_status_yields_spinner_badge_and_accent_colour() {
        val style = NodeStyleResolver.resolve(
            node = node("KSampler"),
            descriptor = descriptor("KSampler"),
            runtimeStatus = NodeRuntimeStatus.RUNNING,
            palette = palette,
        )
        assertEquals(StatusBadge.SPINNER, style.statusBadge)
        assertEquals(palette.statusRunningArgb, NodeStyleResolver.statusAccentArgb(NodeRuntimeStatus.RUNNING, palette))
    }

    @Test fun done_status_yields_check_badge() {
        val style = NodeStyleResolver.resolve(
            node = node("VAEDecode"),
            descriptor = descriptor("VAEDecode"),
            runtimeStatus = NodeRuntimeStatus.DONE,
            palette = palette,
        )
        assertEquals(StatusBadge.DONE, style.statusBadge)
    }

    @Test fun cached_status_yields_cached_badge() {
        val style = NodeStyleResolver.resolve(
            node = node("CheckpointLoaderSimple"),
            descriptor = descriptor("CheckpointLoaderSimple"),
            runtimeStatus = NodeRuntimeStatus.CACHED,
            palette = palette,
        )
        assertEquals(StatusBadge.CACHED, style.statusBadge)
    }

    @Test fun error_status_yields_error_badge() {
        val style = NodeStyleResolver.resolve(
            node = node("KSampler"),
            descriptor = descriptor("KSampler"),
            runtimeStatus = NodeRuntimeStatus.ERROR,
            palette = palette,
        )
        assertEquals(StatusBadge.ERROR, style.statusBadge)
    }

    // ---------------------------------------------------------------- selection

    @Test fun selected_state_uses_primary_border_at_2dp() {
        val style = NodeStyleResolver.resolve(
            node = node("KSampler"),
            descriptor = descriptor("KSampler"),
            runtimeStatus = NodeRuntimeStatus.IDLE,
            palette = palette,
            isSelected = true,
        )
        assertEquals(palette.borderSelectedArgb, style.borderArgb)
        assertEquals(2f, style.borderWidthDp)
        assertTrue(style.showSelected)
    }

    @Test fun unselected_state_uses_default_border_at_1dp() {
        val style = NodeStyleResolver.resolve(
            node = node("KSampler"),
            descriptor = descriptor("KSampler"),
            runtimeStatus = NodeRuntimeStatus.IDLE,
            palette = palette,
            isSelected = false,
        )
        assertEquals(palette.borderArgb, style.borderArgb)
        assertEquals(1f, style.borderWidthDp)
        assertEquals(false, style.showSelected)
    }

    // ---------------------------------------------------------------- ports

    @Test fun port_colour_matches_ComfyUI_link_type_palette() {
        // Per @Ores T2.7 §1.4: MODEL=purple / CLIP=blue / VAE=red /
        // LATENT=green / IMAGE=orange / MASK=dark green /
        // CONDITIONING=beige / CONTROL_NET=dark purple.
        val expectations = listOf(
            "MODEL"        to 0xFF7E57C2,
            "CLIP"         to 0xFF1E88E5,
            "VAE"          to 0xFFE53935,
            "LATENT"       to 0xFF43A047,
            "IMAGE"        to 0xFFFB8C00,
            "MASK"         to 0xFF2E7D32,
            "CONDITIONING" to 0xFFC0A062,
            "CONTROL_NET"  to 0xFF4527A0,
        )
        for ((type, expectedArgb) in expectations) {
            val port = NodePort(slotIndex = 0, name = "p", type = type)
            assertEquals(
                expectedArgb,
                NodeStyleResolver.resolvePort(port, palette).argb,
                "type=$type expected ARGB ${expectedArgb.toString(16)}",
            )
        }
    }

    @Test fun unknown_port_type_falls_back_to_unknown_colour() {
        val port = NodePort(slotIndex = 0, name = "weird", type = "MY_CUSTOM_TYPE")
        val style = NodeStyleResolver.resolvePort(port, palette)
        assertEquals(palette.unknownPortArgb, style.argb)
        assertEquals("MY_CUSTOM_TYPE", style.type)
    }
}
