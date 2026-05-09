package com.comfymobile.data.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Response for `GET /queue`. ComfyUI returns two JsonArray fields
 * which are themselves arrays of arrays — the leading element is a
 * priority number, the second is the prompt id, the third is the
 * API-format prompt JSON.
 *
 * We keep the running/pending arrays as `JsonElement` to avoid
 * coupling DTO parsing to the wire layout (which has changed across
 * ComfyUI versions). Higher-level code extracts the prompt ids via a
 * helper.
 */
@Serializable
data class QueueDto(
    val queue_running: JsonElement? = null,
    val queue_pending: JsonElement? = null,
)
