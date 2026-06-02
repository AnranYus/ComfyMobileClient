package com.comfymobile.domain.workflow

import kotlinx.serialization.json.JsonElement

/**
 * One topology mutation in the Phase 3 editor's op log.
 *
 * Per ADR-0005 §1 the alphabet is exactly four ops. Each subclass
 * carries everything its corresponding inverse needs so undo is a
 * cheap, deterministic transformation — no need to snapshot a
 * whole JSON tree or recompute deltas at undo time.
 *
 * `RemoveNode` is the only compound atomic op (ADR-0005 §6 Q5):
 * it bundles the cascaded link removals into its own log entry so
 * a single `undo()` restores the node *and* every link the
 * cascade dropped. That is "one user action, one undo step" —
 * gesture-grouping (multiple distinct user ops collapsed into one
 * undo via `OpLog.beginGroup() / endGroup()`) is a separate
 * Phase 3.x concept and lives outside this sealed hierarchy.
 *
 * All subclasses are pure data, with no platform or presentation
 * imports, so the domain layer can apply them without depending
 * on Compose / iOS / parser surfaces. (`OpLog`, which manages
 * undo/redo stack semantics, lives in `presentation.graph.editor`
 * because it is editor-session state.)
 */
sealed interface TopologyOp {

    /**
     * Append a new node to `nodes[]`. Pre-condition: `objectInfo`
     * must have an entry for [classType] — `WorkingGraph` enforces
     * that via `canAddNode` per ADR-0005 §6 Q4. The fresh node's
     * default `widgets_values` are minted from
     * `/object_info[classType].input.required` in registration
     * order (T0.1 M2 rules).
     *
     * @property assignedId the id the editor minted for the new
     *   node (sequential `max(existing) + 1` per ADR-0005 §5 V6).
     *   Captured here so undo / redo deterministically address the
     *   same node — without this the redo path would have to
     *   re-mint the id and could collide with later edits.
     * @property posX / posY the workflow-world position the user
     *   dropped the node at. Stored as raw floats so this domain
     *   type stays free of presentation-layer imports.
     */
    data class AddNode(
        val classType: String,
        val posX: Float,
        val posY: Float,
        val assignedId: String,
    ) : TopologyOp

    /**
     * Remove a node and every link incident on it. Compound atomic:
     * a single log entry, undone in one shot. The [cascadedLinks]
     * field captures every `links[]` row that was removed so undo
     * can restore them exactly.
     */
    data class RemoveNode(
        val id: String,
        /**
         * Snapshot of the removed-node payload, captured at op time
         * so undo can re-insert byte-for-byte. We carry the whole
         * node `JsonElement` rather than a typed projection because
         * unknown / custom-node fields must survive — ADR-0003
         * structure-lossless extends through edit sessions per
         * ADR-0005 §2.
         */
        val removedNode: JsonElement,
        /**
         * The `links[]` 6-tuple entries whose `source_node_id` or
         * `target_node_id` matched [id] and were dropped as part of
         * this op. Sorted by `link_id` ascending for stable undo
         * ordering.
         */
        val cascadedLinks: List<JsonElement>,
    ) : TopologyOp

    /**
     * Create a new edge between two ports.
     *
     * @property assignedLinkId the link id the editor minted for the
     *   new link (sequential `max(existing links) + 1`). Same
     *   reasoning as [AddNode.assignedId] — undo/redo address the
     *   same link.
     * @property type the link type token (`"MODEL"`, `"CLIP"`,
     *   `"LATENT"`, …) the source port emits. Recorded here so undo
     *   doesn't have to re-derive it from the source port descriptor.
     */
    data class Connect(
        val sourceNodeId: String,
        val sourceSlot: Int,
        val targetNodeId: String,
        val targetSlot: Int,
        val type: String,
        val assignedLinkId: Int,
    ) : TopologyOp

    /**
     * Drop an existing link. [removedLink] is the full original
     * 6-tuple snapshot so undo can re-insert it byte-for-byte;
     * the source port's `outputs[…].links` list and the target
     * port's `inputs[…].link` field are both restored from this
     * snapshot.
     */
    data class Disconnect(
        val linkId: Int,
        val removedLink: JsonElement,
    ) : TopologyOp
}
