package com.comfymobile.presentation.graph.editor

import com.comfymobile.domain.workflow.TopologyOp

/**
 * The Phase 3 editor's append-only mutation log, with an
 * inverse-op stack for redo.
 *
 * Per ADR-0005 §2 + §6 Q5 the editor's source of truth is
 * `WorkflowEnvelope.original` plus this log; render is
 * `apply(original, log)`. Undo and redo are the only ways the log
 * shrinks/grows from its tail — direct mutation in the middle is
 * not part of the contract.
 *
 * The log is in-memory only (ADR-0005 §6 Q1 parks persistence as
 * a v0.3+ polish), and a single editor session owns one instance.
 *
 * Thread-affinity: not internally synchronised. The intended owner
 * is `WorkingGraph`, which itself is called from the
 * `WorkflowGraphViewModel`'s single coroutine context (per Phase 2
 * VM pattern). Multi-threaded use would need an external mutex.
 */
class OpLog {

    private val applied = ArrayDeque<TopologyOp>()
    private val redoStack = ArrayDeque<TopologyOp>()

    /** Read-only snapshot of currently-applied ops, oldest first. */
    val entries: List<TopologyOp>
        get() = applied.toList()

    /** True if [undo] would change state. */
    val canUndo: Boolean
        get() = applied.isNotEmpty()

    /**
     * True if [redo] would change state. Note: any non-undo
     * mutation (i.e. [append]) clears the redoStack — see
     * [append]'s doc.
     */
    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    /** Number of currently-applied ops. */
    val size: Int
        get() = applied.size

    /**
     * Append a new op to the tail. This clears the redoStack — a
     * mutation made after a series of undos is the canonical
     * "branched timeline" event that invalidates the redo path
     * (matching every text editor's undo/redo behaviour).
     */
    fun append(op: TopologyOp) {
        applied.addLast(op)
        redoStack.clear()
    }

    /**
     * Move the tail op from [applied] to [redoStack]. Returns the
     * op that was undone, or null if the log is empty. The caller
     * (`WorkingGraph`) is responsible for re-rendering with the
     * shorter log; this class only manages the data structure.
     */
    fun undo(): TopologyOp? {
        val op = applied.removeLastOrNull() ?: return null
        redoStack.addLast(op)
        return op
    }

    /**
     * Pop from [redoStack] and re-apply (i.e. push back onto the
     * applied tail). Returns the op that was redone, or null if
     * the redoStack is empty.
     *
     * Distinct from [append]: redo does NOT clear the redoStack
     * (otherwise repeated redos beyond the first would never work).
     */
    fun redo(): TopologyOp? {
        val op = redoStack.removeLastOrNull() ?: return null
        applied.addLast(op)
        return op
    }

    /**
     * Drop the entire log and redoStack. Used by
     * `WorkingGraph.confirm()` and `.resetToImported()` to reset
     * the session to a clean state.
     */
    fun clear() {
        applied.clear()
        redoStack.clear()
    }
}
