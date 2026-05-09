package com.comfymobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure JSON → [WsEvent] decoder for the ComfyUI WebSocket frames.
 *
 * No IO, no networking, no concurrency. The actual WebSocket driver
 * (`ComfyWebSocketClient`, lands in T1.1 part 2) feeds raw text
 * frames here and emits the resulting [WsEvent] downstream. Tests
 * therefore exercise the parser entirely via inline JSON strings.
 *
 * Frames follow the shape:
 * ```
 * { "type": "<name>", "data": { ... } }
 * ```
 * Anything we cannot route to a known [WsEvent] subtype falls back to
 * [WsEvent.Unknown] with the original payload preserved verbatim.
 */
object WsEventParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Parse a raw text frame from the WebSocket into a [WsEvent]. */
    fun parse(frame: String): WsEvent {
        val element = json.parseToJsonElement(frame)
        return parse(element)
    }

    /** Parse an already-decoded [JsonElement] into a [WsEvent]. */
    fun parse(element: JsonElement): WsEvent {
        val obj = element as? JsonObject
            ?: return WsEvent.Unknown(type = "(non-object)", payload = element)
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: return WsEvent.Unknown(type = "(missing-type)", payload = obj)
        val data = obj["data"] ?: JsonObject(emptyMap())

        // For execution events the `data` should be an object; for the
        // Unknown fallback we just hand back whatever we got.
        return when (type) {
            "status" -> parseStatus(data)
            "feature_flags" -> WsEvent.FeatureFlags(flags = data)
            "execution_start" -> parseExecutionStart(data, raw = obj)
            "execution_cached" -> parseExecutionCached(data, raw = obj)
            "executing" -> parseExecuting(data, raw = obj)
            "progress" -> parseProgress(data, raw = obj)
            "progress_state" -> parseProgressState(data, raw = obj)
            "executed" -> parseExecuted(data, raw = obj)
            "execution_error" -> parseExecutionError(data, raw = obj)
            "execution_interrupted" -> parseExecutionInterrupted(data, raw = obj)
            "execution_success" -> parseExecutionSuccess(data, raw = obj)
            else -> WsEvent.Unknown(type = type, payload = obj)
        }
    }

    // ----------------------------------------------------------------- per-type

    private fun parseStatus(data: JsonElement): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("status", data)
        val queueRemaining = obj["status"]?.objectOrNull()
            ?.get("exec_info")?.objectOrNull()
            ?.get("queue_remaining")?.intOrNullSafe()
            ?: 0
        val sid = obj["sid"]?.stringOrNull()
        return WsEvent.Status(queueRemaining = queueRemaining, sid = sid)
    }

    private fun parseExecutionStart(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("execution_start", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_start", raw)
        return WsEvent.ExecutionStart(promptId = promptId)
    }

    private fun parseExecutionCached(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("execution_cached", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_cached", raw)
        val nodes = obj["nodes"]?.stringListOrNull() ?: emptyList()
        return WsEvent.ExecutionCached(promptId = promptId, nodes = nodes)
    }

    private fun parseExecuting(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("executing", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("executing", raw)
        // node may be null (sentinel for "execution finished")
        val node = obj["node"]?.stringOrNullAllowingNull()
        val displayNode = obj["display_node"]?.stringOrNullAllowingNull()
        return WsEvent.Executing(
            promptId = promptId,
            node = node,
            displayNode = displayNode,
        )
    }

    private fun parseProgress(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("progress", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("progress", raw)
        val node = obj["node"]?.stringOrNull()
            ?: return WsEvent.Unknown("progress", raw)
        val value = obj["value"]?.intOrNullSafe()
            ?: return WsEvent.Unknown("progress", raw)
        val max = obj["max"]?.intOrNullSafe()
            ?: return WsEvent.Unknown("progress", raw)
        return WsEvent.Progress(promptId, node, value, max)
    }

    private fun parseProgressState(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("progress_state", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("progress_state", raw)
        val nodes = obj["nodes"] ?: JsonObject(emptyMap())
        return WsEvent.ProgressState(promptId = promptId, nodes = nodes)
    }

    private fun parseExecuted(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("executed", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("executed", raw)
        val node = obj["node"]?.stringOrNull()
            ?: return WsEvent.Unknown("executed", raw)
        val output = obj["output"] ?: JsonObject(emptyMap())
        val displayNode = obj["display_node"]?.stringOrNullAllowingNull()
        return WsEvent.Executed(
            promptId = promptId,
            node = node,
            displayNode = displayNode,
            output = output,
        )
    }

    private fun parseExecutionError(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("execution_error", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_error", raw)
        val nodeId = obj["node_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_error", raw)
        val nodeType = obj["node_type"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_error", raw)
        val executed = obj["executed"]?.stringListOrNull() ?: emptyList()
        val exceptionMessage = obj["exception_message"]?.stringOrNull() ?: ""
        val exceptionType = obj["exception_type"]?.stringOrNull() ?: ""
        return WsEvent.ExecutionError(
            promptId = promptId,
            nodeId = nodeId,
            nodeType = nodeType,
            executed = executed,
            exceptionMessage = exceptionMessage,
            exceptionType = exceptionType,
            traceback = obj["traceback"],
            currentInputs = obj["current_inputs"],
            currentOutputs = obj["current_outputs"],
        )
    }

    private fun parseExecutionInterrupted(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("execution_interrupted", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_interrupted", raw)
        return WsEvent.ExecutionInterrupted(
            promptId = promptId,
            nodeId = obj["node_id"]?.stringOrNullAllowingNull(),
            nodeType = obj["node_type"]?.stringOrNullAllowingNull(),
            executed = obj["executed"]?.stringListOrNull() ?: emptyList(),
        )
    }

    private fun parseExecutionSuccess(data: JsonElement, raw: JsonObject): WsEvent {
        val obj = data.objectOrNull() ?: return WsEvent.Unknown("execution_success", raw)
        val promptId = obj["prompt_id"]?.stringOrNull()
            ?: return WsEvent.Unknown("execution_success", raw)
        return WsEvent.ExecutionSuccess(promptId = promptId)
    }

    // ----------------------------------------------------------------- helpers

    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

    /**
     * Returns the string content of a JSON string primitive, or null
     * if the element is missing, JsonNull, a non-string primitive, an
     * array, or an object.
     */
    private fun JsonElement.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    /**
     * Like [stringOrNull] but treats `null` literal as a valid empty
     * value (returns null) — used for fields that ComfyUI explicitly
     * sets to JSON null (e.g. `executing.node = null` at end of run).
     */
    private fun JsonElement.stringOrNullAllowingNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.contentOrNull == null) return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun JsonElement.intOrNullSafe(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonElement.stringListOrNull(): List<String>? {
        val arr = this as? kotlinx.serialization.json.JsonArray ?: return null
        return arr.mapNotNull { it.stringOrNull() }
    }
}
