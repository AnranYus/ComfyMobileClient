package com.comfymobile.data.network.dto

import kotlinx.serialization.Serializable

/**
 * Subset of `GET /system_stats` we care about. Used as a "is this
 * really ComfyUI?" probe during the connect flow.
 *
 * The DTO itself is lenient — it accepts unknown fields (forward-
 * compat with new ComfyUI versions) and even tolerates missing
 * fields including an empty `devices` list (some headless deployments
 * report no GPUs). The actual signature check happens after parse,
 * inside [com.comfymobile.data.network.ComfyHttpClient.getSystemStats]:
 * a non-null/non-blank `system.comfyui_version` is required, and
 * anything weaker throws `ComfyHttpException.MissingField`. Keeping
 * the DTO lenient + the probe strict means future ComfyUI changes
 * to other fields don't break us, while non-ComfyUI servers that
 * happen to emit a vaguely matching JSON shape are still rejected.
 */
@Serializable
data class SystemStatsDto(
    val system: SystemInfoDto,
    val devices: List<DeviceDto> = emptyList(),
)

@Serializable
data class SystemInfoDto(
    val os: String? = null,
    val ram_total: Long? = null,
    val ram_free: Long? = null,
    val comfyui_version: String? = null,
    val python_version: String? = null,
    val pytorch_version: String? = null,
)

@Serializable
data class DeviceDto(
    val name: String? = null,
    val type: String? = null,
    val index: Int? = null,
    val vram_total: Long? = null,
    val vram_free: Long? = null,
)
