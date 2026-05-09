package com.comfymobile.data.network

import kotlinx.serialization.json.JsonElement

/**
 * One event delivered over the ComfyUI `/ws` channel.
 *
 * Authoritative reference: `docs/architecture/T0.1-comfyui-integration.md` §2.
 *
 * The 9 execution-related events come from `execution.py`'s
 * `server.send_sync` calls. Two control events (`status`,
 * `feature_flags`) come from `server.py`. Anything we don't recognise
 * is bucketed under [Unknown] so a future ComfyUI version can ship
 * new event types without crashing the mobile client.
 *
 * All fields are kept as the original wire types (string ids, integer
 * indices, boxed `JsonElement` for free-form payloads) so downstream
 * consumers can decide how to interpret them.
 */
sealed interface WsEvent {

    // ----------------------------------------------------------------- control

    /**
     * Sent on connect and on every queue-state change. `sid` is set
     * when the server allocated a fresh client id (we always supply
     * one, so usually null).
     */
    data class Status(
        val queueRemaining: Int,
        val sid: String? = null,
    ) : WsEvent

    /**
     * Capability declaration from the server. The whole flag map is
     * preserved as JSON; we do not interpret individual flags yet.
     */
    data class FeatureFlags(
        val flags: JsonElement,
    ) : WsEvent

    // ----------------------------------------------------------------- execution

    /** Workflow accepted, execution about to begin. */
    data class ExecutionStart(
        val promptId: String,
    ) : WsEvent

    /**
     * The listed nodes are cache hits and will not emit
     * `executing`/`executed`. UX should pre-mark them as done.
     */
    data class ExecutionCached(
        val promptId: String,
        val nodes: List<String>,
    ) : WsEvent

    /**
     * A node is starting. `node = null` is a sentinel emitted at the
     * very end of execution meaning "no node currently running".
     */
    data class Executing(
        val promptId: String,
        val node: String?,
        val displayNode: String? = null,
    ) : WsEvent

    /** Per-step progress within a sampling node. */
    data class Progress(
        val promptId: String,
        val node: String,
        val value: Int,
        val max: Int,
    ) : WsEvent

    /** Aggregate progress snapshot, used after reconnect. */
    data class ProgressState(
        val promptId: String,
        val nodes: JsonElement,
    ) : WsEvent

    /** A node finished; [output] holds the per-node output payload. */
    data class Executed(
        val promptId: String,
        val node: String,
        val displayNode: String? = null,
        val output: JsonElement,
    ) : WsEvent

    /**
     * A node threw. [traceback] / [currentInputs] / [currentOutputs]
     * are kept verbatim because the exception payload schema varies
     * across ComfyUI versions and custom nodes.
     */
    data class ExecutionError(
        val promptId: String,
        val nodeId: String,
        val nodeType: String,
        val executed: List<String>,
        val exceptionMessage: String,
        val exceptionType: String,
        val traceback: JsonElement? = null,
        val currentInputs: JsonElement? = null,
        val currentOutputs: JsonElement? = null,
    ) : WsEvent

    /** User cancelled (we issued `/interrupt`). */
    data class ExecutionInterrupted(
        val promptId: String,
        val nodeId: String? = null,
        val nodeType: String? = null,
        val executed: List<String> = emptyList(),
    ) : WsEvent

    /** Authoritative "workflow done" signal. */
    data class ExecutionSuccess(
        val promptId: String,
    ) : WsEvent

    // ----------------------------------------------------------------- fallback

    /**
     * An event whose `type` we don't know about. Preserved verbatim so
     * the receiver can log and ignore (or surface for telemetry).
     */
    data class Unknown(
        val type: String,
        val payload: JsonElement,
    ) : WsEvent
}
