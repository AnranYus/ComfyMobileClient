package com.comfymobile.data.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Body for `POST /prompt`. See `docs/architecture/T0.1-comfyui-integration.md` §3.
 *
 * `prompt` is the API-format workflow JSON (a flat map of
 * `node_id` → `{ class_type, inputs }`); `extra_data` carries the
 * `extra_pnginfo.workflow` snapshot we want embedded in generated
 * PNGs (the "current edited UI snapshot" — see ADR-0003).
 *
 * Both are `JsonElement` so the workflow data layer (T1.2) can build
 * arbitrary structures without DTO churn.
 */
@Serializable
data class PromptRequestDto(
    val prompt: JsonElement,
    val client_id: String,
    val prompt_id: String? = null,
    val number: Int? = null,
    val front: Boolean? = null,
    val partial_execution_targets: List<String>? = null,
    val extra_data: JsonElement? = null,
)

/**
 * Successful response for `POST /prompt`. ComfyUI returns the
 * accepted prompt id, the queue position number, and a per-node
 * validation error map (empty when everything was accepted).
 */
@Serializable
data class PromptResponseDto(
    val prompt_id: String,
    val number: Int,
    val node_errors: Map<String, JsonElement> = emptyMap(),
)

/** Body for `POST /queue {delete: [...]}`. */
@Serializable
data class QueueDeleteRequestDto(
    val delete: List<String>,
)

/** Body for `POST /queue {clear: true}`. */
@Serializable
data class QueueClearRequestDto(
    val clear: Boolean = true,
)

/**
 * Body for `POST /interrupt`. Optional [prompt_id] targets a specific
 * running prompt; absence interrupts whatever is currently executing.
 */
@Serializable
data class InterruptRequestDto(
    val prompt_id: String? = null,
)
