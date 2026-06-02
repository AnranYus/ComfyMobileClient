package com.comfymobile.presentation.graph.editor

import com.comfymobile.domain.workflow.TopologyOp
import com.comfymobile.domain.workflow.WorkflowConverter
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowGraph
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 3 editor facade for one workflow.
 *
 * Per ADR-0005 §3 this is the mutable handle the
 * `WorkflowGraphViewModel` hands off to UI gestures. The
 * underlying source of truth is the [envelope]'s current head
 * (`original_json`) plus an [OpLog]; on every render the editor
 * shows the result of `converter.applyTopologyOps(head, log)`.
 *
 * ## Lifecycle
 *
 * - Construction binds an [envelope] (current head) **and** an
 *   [importedOriginal] (the as-imported JSON — the rollback anchor
 *   ADR-0005 §2 introduced to preserve T0.3 "reset to imported").
 *   On a fresh import these are equal; after the user confirms an
 *   edit session they diverge.
 *
 * - [confirm] folds the log into a new envelope with
 *   `original = applyTopologyOps(head, log)` and resets the log;
 *   [importedOriginal] is untouched.
 *
 * - [resetToImported] rebuilds the envelope from [importedOriginal]
 *   and clears the log + redoStack.
 *
 * ## Threading
 *
 * Single-threaded — the caller (typically `WorkflowGraphViewModel`'s
 * scope) is responsible for serialising calls. Mutation methods
 * return immediately; reads of [renderedUi] / [renderedApi] /
 * [canUndo] etc. observe the current state without locking.
 *
 * ## Validation
 *
 * The §5 V1..V7 surface (`validation: StateFlow<ValidationResult>`)
 * is deferred to a follow-up task; this class exposes a TODO seam
 * (`currentValidation`) that returns a placeholder so the editor
 * UI can wire its banner without blocking on the validation
 * implementation.
 */
class WorkingGraph(
    private val initialEnvelope: WorkflowEnvelope,
    private val importedOriginal: JsonElement,
    private val converter: WorkflowConverter = WorkflowConverter(),
    private val objectInfo: JsonElement? = null,
) {

    /** Active envelope head — mutated only by [confirm] / [resetToImported]. */
    private var envelope: WorkflowEnvelope = initialEnvelope

    /** Mutation log for the current session; empty after construct / confirm / reset. */
    private val log = OpLog()

    init {
        require(envelope.format == WorkflowFormat.UI) {
            "WorkingGraph requires a UI-format envelope; API-format workflows are run-only " +
                "in Phase 3 (see ADR-0005 §1 out-of-scope)."
        }
    }

    // --- observable state (read-only snapshots) -------------------------------

    /**
     * Current head + log applied, as a UI-format graph. Recomputed
     * on every read for now; the §3 [`StateFlow`] reactive surface
     * is layered on by the VM.
     */
    val renderedUi: WorkflowGraph.Ui
        get() = converter.applyTopologyOps(headUi(), log.entries, objectInfo)

    /** Same as [renderedUi] folded through the existing `uiToApi` converter. */
    val renderedApi: WorkflowGraph.Api
        get() = converter.uiToApi(renderedUi, objectInfo)

    val canUndo: Boolean get() = log.canUndo
    val canRedo: Boolean get() = log.canRedo

    /**
     * Per ADR-0005 §6 Q4: when `/object_info[classType]` is
     * unavailable for a given class, `AddNode` for that class must
     * be blocked at the editor level (the op has no defensible
     * fallback for "what widgets does this class have").
     *
     * Returns `true` when [objectInfo] has an entry for [classType]
     * — i.e. `addNode(classType, …)` is safe to call.
     */
    fun canAddNode(classType: String): Boolean {
        val info = objectInfo as? JsonObject ?: return false
        return classType in info
    }

    // --- mutation ops ---------------------------------------------------------

    /**
     * Append a new node. Mints a fresh node id =
     * `max(existing) + 1` over both the head graph and any pending
     * AddNode ops in the log.
     *
     * Throws [IllegalStateException] when [objectInfo] has no entry
     * for [classType] — the editor must consult [canAddNode] first.
     */
    fun addNode(classType: String, posX: Float, posY: Float): String {
        check(canAddNode(classType)) {
            "AddNode pre-condition failed: /object_info[$classType] is not available. " +
                "Editor must guard with canAddNode($classType) — see ADR-0005 §6 Q4."
        }
        val assignedId = nextNodeId().toString()
        log.append(TopologyOp.AddNode(classType, posX, posY, assignedId))
        return assignedId
    }

    /**
     * Remove a node and cascade-drop every incident link. The
     * cascade is captured inside the [TopologyOp.RemoveNode] entry
     * so `undo()` restores the node and every dropped link in one
     * step (ADR-0005 §6 Q5 compound atomic op).
     *
     * Returns the cascaded link ids in ascending order so the
     * caller (UI) can surface a "removed N links" summary.
     */
    fun removeNode(nodeId: String): List<Int> {
        val currentUi = renderedUi
        val nodes = (currentUi.raw["nodes"] as? JsonArray) ?: JsonArray(emptyList())
        val targetIdInt = nodeId.toIntOrNull()
        val nodeEntry = nodes.firstOrNull { node ->
            val obj = node as? JsonObject ?: return@firstOrNull false
            obj["id"]?.jsonPrimitive?.intOrNull == targetIdInt
        }
        checkNotNull(nodeEntry) { "removeNode: no node with id=$nodeId in the current graph." }

        val links = (currentUi.raw["links"] as? JsonArray) ?: JsonArray(emptyList())
        val cascaded = links.filter { entry ->
            val arr = entry as? JsonArray ?: return@filter false
            if (arr.size < 5) return@filter false
            val src = (arr[1] as? JsonPrimitive)?.intOrNull
            val dst = (arr[3] as? JsonPrimitive)?.intOrNull
            src == targetIdInt || dst == targetIdInt
        }.sortedBy { (it as JsonArray)[0].jsonPrimitive.intOrNull ?: 0 }

        log.append(TopologyOp.RemoveNode(nodeId, nodeEntry, cascaded))
        return cascaded.map { (it as JsonArray)[0].jsonPrimitive.intOrNull ?: 0 }
    }

    /**
     * Append a link. Mints a fresh link id = `max(existing) + 1`
     * over both the head graph's `links[]` and any pending Connect
     * ops in the log.
     */
    fun connect(
        sourceNodeId: String,
        sourceSlot: Int,
        targetNodeId: String,
        targetSlot: Int,
        type: String,
    ): Int {
        val assignedLinkId = nextLinkId()
        log.append(TopologyOp.Connect(sourceNodeId, sourceSlot, targetNodeId, targetSlot, type, assignedLinkId))
        return assignedLinkId
    }

    /**
     * Drop an existing link. The full 6-tuple snapshot is captured
     * in the log entry so `undo()` can re-insert it byte-for-byte.
     */
    fun disconnect(linkId: Int) {
        val currentUi = renderedUi
        val links = (currentUi.raw["links"] as? JsonArray) ?: JsonArray(emptyList())
        val entry = links.firstOrNull { row ->
            val arr = row as? JsonArray ?: return@firstOrNull false
            (arr.getOrNull(0) as? JsonPrimitive)?.intOrNull == linkId
        }
        checkNotNull(entry) { "disconnect: no link with id=$linkId in the current graph." }
        log.append(TopologyOp.Disconnect(linkId, entry))
    }

    // --- undo / redo ----------------------------------------------------------

    fun undo(): TopologyOp? = log.undo()
    fun redo(): TopologyOp? = log.redo()

    // --- session boundaries ---------------------------------------------------

    /**
     * Fold the log into the envelope's `original_json`, clear log
     * and redoStack, and return the new envelope. The
     * [importedOriginal] anchor is untouched — a subsequent
     * [resetToImported] still restores the as-imported state per
     * ADR-0005 §2.
     */
    fun confirm(): WorkflowEnvelope {
        val folded = converter.applyTopologyOps(headUi(), log.entries, objectInfo)
        envelope = envelope.copy(original = folded.raw)
        log.clear()
        return envelope
    }

    /**
     * T0.3 "reset to imported" UX safety valve. Rebuilds the
     * envelope from [importedOriginal] (set at construction time);
     * the in-flight log is discarded. Available regardless of
     * whether [confirm] has run on this instance.
     */
    fun resetToImported(): WorkflowEnvelope {
        envelope = envelope.copy(original = importedOriginal)
        log.clear()
        return envelope
    }

    // --- internal -------------------------------------------------------------

    private fun headUi(): WorkflowGraph.Ui {
        val head = envelope.original
        require(head is JsonObject) {
            "WorkingGraph.headUi: envelope.original must be a JsonObject (got ${head::class.simpleName})."
        }
        return WorkflowGraph.Ui(raw = head)
    }

    private fun nextNodeId(): Int {
        val head = (envelope.original as? JsonObject)?.get("nodes") as? JsonArray
        val headMax = head.orEmpty().mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull }.maxOrNull() ?: 0
        val logMax = log.entries.filterIsInstance<TopologyOp.AddNode>()
            .mapNotNull { it.assignedId.toIntOrNull() }
            .maxOrNull() ?: 0
        return maxOf(headMax, logMax) + 1
    }

    private fun nextLinkId(): Int {
        val head = (envelope.original as? JsonObject)?.get("links") as? JsonArray
        val headMax = head.orEmpty().mapNotNull { (it as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.intOrNull }.maxOrNull() ?: 0
        val logMax = log.entries.filterIsInstance<TopologyOp.Connect>().maxOfOrNull { it.assignedLinkId } ?: 0
        return maxOf(headMax, logMax) + 1
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
