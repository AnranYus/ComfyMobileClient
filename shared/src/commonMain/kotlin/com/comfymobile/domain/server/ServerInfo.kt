package com.comfymobile.domain.server

import kotlinx.serialization.Serializable

/**
 * One LAN ComfyUI server the user has connected to. Persisted in the
 * server history so the user picks it from a friendly list rather
 * than re-typing the IP next time.
 *
 * [serverId] is a stable identifier derived from `host:port`; jobs
 * in the local index reference it for per-server isolation. Friendly
 * label is editable; host/port are not.
 */
@Serializable
data class ServerInfo(
    val serverId: String,
    val host: String,
    val port: Int,
    val label: String,
    val lastConnectedAtEpochMs: Long,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535, got $port" }
    }

    val baseUrl: String
        get() = "http://$host:$port"

    companion object {
        /**
         * Build a stable [serverId] from `host:port`. Two servers
         * differing only in label collapse to the same id; the label
         * is metadata, not identity. The id is deliberately a plain
         * `host:port` string rather than a UUID so it's
         * human-debuggable when logged.
         */
        fun idFor(host: String, port: Int): String = "$host:$port"
    }
}
