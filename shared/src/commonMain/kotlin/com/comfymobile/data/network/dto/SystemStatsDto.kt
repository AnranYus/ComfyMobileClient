package com.comfymobile.data.network.dto

import kotlinx.serialization.Serializable

/**
 * Subset of `GET /system_stats` we care about. Used as a "is this
 * really ComfyUI?" probe during the connect flow — the presence of a
 * `system.comfyui_version` field plus a `devices` array is the
 * defining signature.
 *
 * Lenient on unknown fields so the client tolerates new fields ComfyUI
 * may add later. Strict on the version + devices presence so the
 * NOT_COMFYUI classification works.
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
