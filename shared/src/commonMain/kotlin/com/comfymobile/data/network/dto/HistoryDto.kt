package com.comfymobile.data.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One entry in `/history` keyed by prompt id. ComfyUI returns the
 * full submitted prompt, per-node outputs, and a status block.
 *
 * Outputs and prompt are kept as `JsonElement` because their shapes
 * vary across node types — the workflow data layer (T1.2) and the
 * gallery layer (T1.3) parse them on demand.
 */
@Serializable
data class HistoryEntryDto(
    val prompt: JsonElement? = null,
    val outputs: JsonElement? = null,
    val status: HistoryStatusDto? = null,
)

@Serializable
data class HistoryStatusDto(
    val status_str: String? = null,
    val completed: Boolean? = null,
    val messages: JsonElement? = null,
)

/**
 * `/history` (without prompt id) is `Map<promptId, HistoryEntryDto>`;
 * order is most-recent-first as documented in T0.1 §4.
 */
typealias HistoryMap = Map<String, HistoryEntryDto>
