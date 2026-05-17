package com.comfymobile.domain.job

/**
 * Status lifecycle for a generation job tracked in the local index.
 *
 *   queued      → submitted to the server, not yet executing
 *   running     → server is currently processing this prompt
 *   succeeded   → terminal: server reported execution_success
 *   failed      → terminal: server reported execution_error
 *   interrupted → terminal: user cancelled / runner cancelled
 *
 * Terminal statuses are exactly { succeeded, failed, interrupted };
 * the reconciler refuses to overwrite a row already in any of these.
 */
enum class JobStatus(val wireValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    INTERRUPTED("interrupted");

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == INTERRUPTED

    companion object {
        /**
         * Parse a status string from the SQLDelight column.
         * Accepts the canonical lowercased names; throws on unknown
         * values rather than silently downgrading.
         */
        fun fromWire(value: String): JobStatus =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown JobStatus wire value: $value")
    }
}

/**
 * First image-like output attached to a job, stored in the local
 * index so history rows can resolve a thumbnail without asking
 * ComfyUI `/history` every time.
 */
data class JobOutputRef(
    val filename: String,
    val subfolder: String = "",
    val type: String = TYPE_OUTPUT,
) {
    companion object {
        const val TYPE_OUTPUT: String = "output"
        const val TYPE_INPUT: String = "input"
        const val TYPE_TEMP: String = "temp"
    }
}

/**
 * One row in the local job index. Mirrors the SQLDelight `jobIndex`
 * table but exposes a strongly-typed [JobStatus] enum and `Long?`
 * timestamps converted from the underlying nullable integer column.
 */
data class Job(
    val promptId: String,
    val serverId: String,
    val status: JobStatus,
    val workflowSnapshotJson: String? = null,
    val apiPromptJson: String? = null,
    val label: String? = null,
    val firstOutput: JobOutputRef? = null,
    val isFavorite: Boolean = false,
    val createdAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
)
