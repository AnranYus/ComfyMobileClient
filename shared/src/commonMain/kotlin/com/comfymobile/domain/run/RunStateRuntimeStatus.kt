package com.comfymobile.domain.run

import com.comfymobile.presentation.graph.NodeRuntimeStatus

/**
 * Project [RunState] into the `Map<String, NodeRuntimeStatus>` that
 * [com.comfymobile.presentation.graph.GraphCanvas] consumes for per-node
 * status highlighting.
 *
 * The follow-up T2.3 wiring PR will plug this between
 * [com.comfymobile.domain.run.RunCoordinator.state] and the existing
 * `GraphCanvas` render layer; lives here (pure, no Compose) so the
 * wiring is one line and the rules are unit-testable.
 *
 * Rules (per T0.4 §3.2 + T2.7 §3.2 — graph annotation states):
 *  - Cached nodes (⚡)             → [NodeRuntimeStatus.CACHED]
 *  - Currently executing node     → [NodeRuntimeStatus.RUNNING]
 *  - Completed (executed) nodes   → [NodeRuntimeStatus.DONE]
 *  - Failed node (from execution_error) → [NodeRuntimeStatus.ERROR]
 *  - Everything else              → no entry (caller defaults to IDLE)
 *
 * Precedence when a node appears in multiple sets:
 *  ERROR  >  RUNNING  >  DONE  >  CACHED
 * (A failed node should always show error, never overwritten by stale
 * completed/cached marks. A cached node that later runs (rare) is
 * marked RUNNING.)
 *
 * Pure-function; no allocation beyond the result map.
 */
fun RunState.toRuntimeStatusMap(): Map<String, NodeRuntimeStatus> {
    val result = mutableMapOf<String, NodeRuntimeStatus>()

    fun apply(nodeId: String, status: NodeRuntimeStatus) {
        val existing = result[nodeId]
        result[nodeId] = pickHigherPrecedence(existing, status)
    }

    when (this) {
        is RunState.Idle, is RunState.Submitting -> return emptyMap()
        is RunState.Queued -> return emptyMap()
        is RunState.Running -> {
            cachedNodes.forEach { apply(it, NodeRuntimeStatus.CACHED) }
            completedNodes.forEach { apply(it, NodeRuntimeStatus.DONE) }
            currentNodeId?.let { apply(it, NodeRuntimeStatus.RUNNING) }
        }
        is RunState.Succeeded -> {
            // Terminal: render everything we know about as DONE/CACHED so
            // the graph reflects the final state. (currentNodeId is null
            // after execution_success.)
            // We don't track them in Succeeded; this is intentionally a
            // no-op for now — the success transition surface is the
            // output gallery, not the graph annotation.
            return emptyMap()
        }
        is RunState.Failed -> {
            val nodeId = (error as? RunError.NodeException)?.nodeId ?: return emptyMap()
            apply(nodeId, NodeRuntimeStatus.ERROR)
        }
        is RunState.Cancelled -> {
            fromNodeId?.let { apply(it, NodeRuntimeStatus.RUNNING) } // shown faded by Cancelled overlay
        }
    }
    return result
}

private fun pickHigherPrecedence(
    existing: NodeRuntimeStatus?,
    incoming: NodeRuntimeStatus,
): NodeRuntimeStatus {
    if (existing == null) return incoming
    return when {
        existing == NodeRuntimeStatus.ERROR || incoming == NodeRuntimeStatus.ERROR -> NodeRuntimeStatus.ERROR
        existing == NodeRuntimeStatus.RUNNING || incoming == NodeRuntimeStatus.RUNNING -> NodeRuntimeStatus.RUNNING
        existing == NodeRuntimeStatus.DONE || incoming == NodeRuntimeStatus.DONE -> NodeRuntimeStatus.DONE
        else -> NodeRuntimeStatus.CACHED
    }
}
