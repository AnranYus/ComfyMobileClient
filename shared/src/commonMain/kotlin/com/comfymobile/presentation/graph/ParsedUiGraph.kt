package com.comfymobile.presentation.graph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Normalised mobile-side projection of [com.comfymobile.domain.workflow.WorkflowGraph.Ui].
 *
 * `WorkflowGraph.Ui` keeps the original ComfyUI editor JSON
 * structure-lossless as a `JsonObject`; that's the right format for
 * round-trip safety, but the render layer needs a typed flat shape
 * to compute layout / styles / draw commands. [ParsedUiGraph] is
 * that typed shape — produced by [UiGraphParser] from the raw JSON.
 *
 * Per @Lily PR #19 thread (`4da46760`) T2.1a guidance: the layout
 * layer's input must be a stable, serializable domain model with no
 * Compose `Dp` / `Offset` / mutable state. `ParsedUiGraph` satisfies
 * that — it's pure data, kotlinx-Serializable, no platform types.
 */
@Serializable
data class ParsedUiGraph(
    val nodes: List<ParsedNode>,
    val links: List<ParsedLink>,
)

/**
 * One node from the editor save format.
 *
 * @property id String form of the integer id ComfyUI assigns
 *   (e.g. "1", "10"). We keep it as String so it shares a wire shape
 *   with [com.comfymobile.domain.workflow.WorkflowGraph.Api]'s string
 *   keys.
 * @property classType ComfyUI `class_type` (e.g. "CheckpointLoaderSimple").
 * @property title User-overridable title (`title` field in editor save);
 *   `null` if the user hasn't renamed the node.
 * @property originalPos The `[x, y]` ComfyUI saved with the workflow
 *   in the `pos` field. `null` when the source was API format or the
 *   field was missing — the layout layer falls back to its own
 *   placement strategy in that case.
 * @property originalSize The `[width, height]` from the editor save,
 *   if present. `null` falls back to the layout default node size.
 * @property inputs Ordered input ports the node exposes (slot 0..n-1).
 *   Empty list when the editor save didn't include input metadata
 *   (rare; happens with very old workflows).
 * @property outputs Ordered output ports.
 */
@Serializable
data class ParsedNode(
    val id: String,
    val classType: String,
    val title: String? = null,
    val originalPos: Position? = null,
    val originalSize: Size? = null,
    val inputs: List<NodePort> = emptyList(),
    val outputs: List<NodePort> = emptyList(),
    /**
     * The ComfyUI editor save's `widgets_values` array, in declaration
     * order — slot 0 maps to descriptor.editableParams[0] etc.
     *
     * Kept as raw [JsonElement] (number / string / bool / null /
     * nested object) because (a) the value type varies per control
     * (Slider → Double, Integer → Long, MultilineText → String,
     * Toggle → Boolean) and (b) we want to round-trip the original
     * value structurally — see ADR-0003 on structure-lossless
     * preservation.
     *
     * The presentation layer's `SummaryRowResolver` is responsible
     * for formatting each entry into a single display line.
     *
     * Empty when the editor save didn't record widget values (rare —
     * happens with very old workflows or for nodes that have no
     * widgets).
     */
    val widgetsValues: List<JsonElement> = emptyList(),
)

/**
 * One port on a node — either an input or an output slot.
 *
 * @property slotIndex 0-based position in the node's input or output
 *   list. Used to identify the port across the
 *   [ParsedLink.sourceSlot] / [ParsedLink.targetSlot] pair.
 * @property name Human-readable label ("model", "positive", "samples").
 *   Used for the port tooltip / accessibility description.
 * @property type ComfyUI link type token (`MODEL`, `CLIP`, `VAE`,
 *   `LATENT`, `IMAGE`, `MASK`, `CONDITIONING`, `CONTROL_NET`, …).
 *   Drives the port colour per @Ores T2.7 §1.4.
 */
@Serializable
data class NodePort(
    val slotIndex: Int,
    val name: String,
    val type: String,
)

/**
 * One link / edge in the editor's `links` array.
 *
 * ComfyUI saves links as 6-tuples
 * `[link_id, source_node_id, source_slot, target_node_id, target_slot, type]`.
 * We unpack them into named fields so the layout / render layers
 * don't have to remember positional indices.
 *
 * @property linkId Stable id ComfyUI assigned to the link. Used as
 *   the key in `LayoutResult.edges` so callers can address one
 *   specific edge for highlighting / animation.
 * @property type Link type token, same vocabulary as
 *   [NodePort.type]. The link should always carry the type the source
 *   port emits; the render layer asserts they match for sanity.
 */
@Serializable
data class ParsedLink(
    val linkId: String,
    val sourceNodeId: String,
    val sourceSlot: Int,
    val targetNodeId: String,
    val targetSlot: Int,
    val type: String,
)

/**
 * 2D coordinate in workflow-world units (ComfyUI-style float pixels).
 *
 * Kept as a plain data class so it survives kotlinx-serialisation and
 * is trivial to copy across the test boundary. **No Compose
 * dependency** — see class-level KDoc on [ParsedUiGraph].
 */
@Serializable
data class Position(val x: Float, val y: Float)

/**
 * 2D extent in workflow-world units. Width × height as floats so
 * arbitrary node sizes from the editor save are round-trippable.
 */
@Serializable
data class Size(val width: Float, val height: Float)
