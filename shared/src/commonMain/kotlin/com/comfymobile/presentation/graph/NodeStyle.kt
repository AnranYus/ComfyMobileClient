package com.comfymobile.presentation.graph

import com.comfymobile.domain.node.NodeDescriptor

/**
 * Per-node visual style resolved from descriptor + runtime status.
 *
 * Pure data — no Compose `Color` import — so unit tests can assert
 * exact ARGB values without a UI scaffold (per @Lily PR #19 thread
 * `4da46760` point 1).
 *
 * Colours are encoded as ARGB `Long` (0xAARRGGBB). The Compose-side
 * Canvas converts each to `androidx.compose.ui.graphics.Color`.
 */
data class NodeStyle(
    /** Body fill — Material 3 surfaceContainer / surfaceContainerHigh. */
    val fillArgb: Long,
    /** Title-bar tint, distinct from body so the user sees a header band. */
    val titleArgb: Long,
    /** 1dp default; 2dp when [showSelected]. */
    val borderArgb: Long,
    val borderWidthDp: Float,
    /** Status badge to draw in the title bar's right edge (per @Ores §1.2). */
    val statusBadge: StatusBadge,
    /** Whitelisted nodes render full body; unknown nodes collapse to title-only italic. */
    val bodyMode: BodyMode,
    /** True when the user has selected the node — adds a primary outline. */
    val showSelected: Boolean,
)

enum class StatusBadge { NONE, SPINNER, DONE, CACHED, ERROR }

enum class BodyMode { FULL, TITLE_ONLY }

/**
 * Per-port colour resolved by ComfyUI link type.
 *
 * Per @Ores T2.7 §1.4: colours match the desktop editor so users
 * coming from the web client can identify connections at a glance.
 */
data class PortStyle(
    val argb: Long,
    val type: String,
)

/**
 * Runtime status of one node from the running prompt's WS events.
 * Drives [StatusBadge] selection in [NodeStyleResolver.resolve].
 *
 *  - [IDLE] — no signal. The default before / after a run.
 *  - [RUNNING] — `executing { node: id }` was emitted.
 *  - [DONE] — `executed` for this node.
 *  - [ERROR] — `execution_error` referenced this node.
 *  - [CACHED] — the executor reported this node as a cache hit
 *    (`execution_cached` event lists the node id).
 */
enum class NodeRuntimeStatus { IDLE, RUNNING, DONE, ERROR, CACHED }

/**
 * Resolved palette decoupled from Compose's colorScheme. Production
 * passes a palette built from the active Material 3 theme; tests
 * pass [defaultLightForTesting].
 *
 * The palette splits into two bands (per @Ores §1.4 / §1.2):
 *  - **typeColors** drive port + edge colour by ComfyUI link type.
 *  - **statusColors** drive runtime status badges + title accents.
 */
data class GraphPalette(
    val nodeFillArgb: Long,
    val nodeFillUnknownArgb: Long,
    val titleArgb: Long,
    val titleUnknownArgb: Long,
    val borderArgb: Long,
    val borderSelectedArgb: Long,
    val statusRunningArgb: Long,
    val statusDoneArgb: Long,
    val statusCachedArgb: Long,
    val statusErrorArgb: Long,
    /**
     * ComfyUI link-type → ARGB. Unknown types fall back to
     * [unknownPortArgb]. Per @Ores T2.7 §1.4.
     */
    val typeColors: Map<String, Long>,
    val unknownPortArgb: Long,
) {
    companion object {
        /**
         * Light-theme defaults derived from the @Ores T2.7 §1.4 colour
         * spec. Production callers replace these with values pulled
         * from the active Material 3 [androidx.compose.material3.ColorScheme].
         */
        val defaultLightForTesting: GraphPalette = GraphPalette(
            nodeFillArgb = 0xFFF5F5F5,
            nodeFillUnknownArgb = 0xFFE0E0E0,
            titleArgb = 0xFFE0E0E0,
            titleUnknownArgb = 0xFFCCCCCC,
            borderArgb = 0xFFB0B0B0,
            borderSelectedArgb = 0xFF1976D2,
            statusRunningArgb = 0xFF1976D2,
            statusDoneArgb = 0xFF388E3C,
            statusCachedArgb = 0xFFFFA000,
            statusErrorArgb = 0xFFD32F2F,
            typeColors = mapOf(
                "MODEL"        to 0xFF7E57C2,  // purple
                "CLIP"         to 0xFF1E88E5,  // blue
                "VAE"          to 0xFFE53935,  // red
                "LATENT"       to 0xFF43A047,  // green
                "IMAGE"        to 0xFFFB8C00,  // orange
                "MASK"         to 0xFF2E7D32,  // dark green
                "CONDITIONING" to 0xFFC0A062,  // beige
                "CONTROL_NET"  to 0xFF4527A0,  // dark purple
            ),
            unknownPortArgb = 0xFF9E9E9E,
        )
    }
}

object NodeStyleResolver {

    /**
     * Resolve [NodeStyle] for a single node. Pure function — caller
     * threads the runtime-status map and palette in; we never query
     * any global state.
     */
    fun resolve(
        node: ParsedNode,
        descriptor: NodeDescriptor?,
        runtimeStatus: NodeRuntimeStatus,
        palette: GraphPalette,
        isSelected: Boolean = false,
    ): NodeStyle {
        val isUnknown = descriptor == null
        return NodeStyle(
            fillArgb = if (isUnknown) palette.nodeFillUnknownArgb else palette.nodeFillArgb,
            titleArgb = if (isUnknown) palette.titleUnknownArgb else palette.titleArgb,
            borderArgb = if (isSelected) palette.borderSelectedArgb else palette.borderArgb,
            borderWidthDp = if (isSelected) 2f else 1f,
            statusBadge = when (runtimeStatus) {
                NodeRuntimeStatus.RUNNING -> StatusBadge.SPINNER
                NodeRuntimeStatus.DONE -> StatusBadge.DONE
                NodeRuntimeStatus.CACHED -> StatusBadge.CACHED
                NodeRuntimeStatus.ERROR -> StatusBadge.ERROR
                NodeRuntimeStatus.IDLE -> StatusBadge.NONE
            },
            bodyMode = if (isUnknown) BodyMode.TITLE_ONLY else BodyMode.FULL,
            showSelected = isSelected,
        )
    }

    /**
     * Resolve [PortStyle] for one [NodePort]. Unknown link types fall
     * back to [GraphPalette.unknownPortArgb] — useful for custom-node
     * outputs whose type token isn't in the standard vocabulary.
     */
    fun resolvePort(port: NodePort, palette: GraphPalette): PortStyle {
        val argb = palette.typeColors[port.type] ?: palette.unknownPortArgb
        return PortStyle(argb = argb, type = port.type)
    }

    /**
     * Status colour for the title-bar accent / running-progress
     * indicator on a node. Returns null for [NodeRuntimeStatus.IDLE]
     * so callers can omit accent drawing.
     */
    fun statusAccentArgb(
        runtimeStatus: NodeRuntimeStatus,
        palette: GraphPalette,
    ): Long? = when (runtimeStatus) {
        NodeRuntimeStatus.IDLE -> null
        NodeRuntimeStatus.RUNNING -> palette.statusRunningArgb
        NodeRuntimeStatus.DONE -> palette.statusDoneArgb
        NodeRuntimeStatus.CACHED -> palette.statusCachedArgb
        NodeRuntimeStatus.ERROR -> palette.statusErrorArgb
    }
}
