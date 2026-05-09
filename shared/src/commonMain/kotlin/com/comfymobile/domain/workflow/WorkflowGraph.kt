package com.comfymobile.domain.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Two-faced workflow representation. The [Ui] form mirrors the
 * desktop ComfyUI editor's save format (with positions, links,
 * widgets_values, viewport); the [Api] form is the flat shape that
 * `POST /prompt` accepts.
 *
 * The mobile app reads either shape on import, but always emits API
 * shape on submit. See `WorkflowConverter` for the UI → API rules.
 */
@Serializable
sealed interface WorkflowGraph {

    /**
     * Verbatim UI format. Stored as `JsonObject` rather than a
     * strongly typed model so unknown fields (custom node types,
     * future ComfyUI extensions) round-trip safely.
     */
    @Serializable
    @SerialName("ui")
    data class Ui(val raw: JsonObject) : WorkflowGraph

    /**
     * API-format graph: `{ node_id_string -> ApiNode }`. Iteration
     * order is the order ComfyUI returned the keys; in API submissions
     * order does not matter to the server but we keep insertion order
     * for diffability.
     */
    @Serializable
    @SerialName("api")
    data class Api(val nodes: Map<String, ApiNode>) : WorkflowGraph
}

/**
 * One node in API format.
 *
 * `inputs` values are either:
 *  - a literal JSON primitive / array / object, or
 *  - a 2-element `[source_node_id_string, source_output_index_int]`
 *    link tuple expressed as a JSON array.
 *
 * We keep them as [JsonElement] so unknown shapes (e.g. partial-execution
 * metadata) survive without DTO changes.
 */
@Serializable
data class ApiNode(
    @SerialName("class_type") val classType: String,
    val inputs: Map<String, JsonElement> = emptyMap(),
)
