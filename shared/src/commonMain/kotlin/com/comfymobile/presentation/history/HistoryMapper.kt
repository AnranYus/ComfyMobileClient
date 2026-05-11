package com.comfymobile.presentation.history

import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobStatus
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun interface HistoryThumbnailMapper {
    fun map(output: JobOutputRef): String?
}

object NoopHistoryThumbnailMapper : HistoryThumbnailMapper {
    override fun map(output: JobOutputRef): String? = null
}

object HistoryMapper {
    private val json = Json { ignoreUnknownKeys = true }
    private val promptKeys = setOf("text", "prompt", "positive", "negative")

    fun rows(
        jobs: List<Job>,
        selectedFilter: HistoryFilter,
        language: ConnectionLanguage,
        nowEpochMs: Long,
        thumbnailMapper: HistoryThumbnailMapper = NoopHistoryThumbnailMapper,
    ): List<HistoryRowState> =
        jobs
            .filter { it.matches(selectedFilter) }
            .map { job ->
                HistoryRowState(
                    promptId = job.promptId,
                    title = job.label?.takeIf { it.isNotBlank() } ?: "Prompt ${job.promptId.take(8)}",
                    promptSnippet = promptSnippet(job.apiPromptJson ?: job.workflowSnapshotJson, language),
                    relativeTime = relativeTime(job.createdAtEpochMs, nowEpochMs, language),
                    status = status(job.status),
                    thumbnailUrl = job.firstOutput?.let { thumbnailMapper.map(it) },
                    canOpenWorkflow = !job.workflowSnapshotJson.isNullOrBlank(),
                )
            }

    fun promptSnippet(apiPromptJson: String?, language: ConnectionLanguage): String {
        val fallback = HistoryCopy.promptMissing.resolve(language)
        if (apiPromptJson.isNullOrBlank()) return fallback
        val text = runCatching {
            firstPromptText(json.parseToJsonElement(apiPromptJson))
        }.getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return fallback
        return if (text.length <= 60) text else text.take(60).trimEnd() + "..."
    }

    fun relativeTime(createdAtEpochMs: Long, nowEpochMs: Long, language: ConnectionLanguage): String {
        val deltaSeconds = ((nowEpochMs - createdAtEpochMs).coerceAtLeast(0L) / 1000L)
        if (deltaSeconds < 60L) return HistoryCopy.justNow.resolve(language)
        val minutes = deltaSeconds / 60L
        if (minutes < 60L) {
            return when (language) {
                ConnectionLanguage.Zh -> "${minutes} 分钟前"
                ConnectionLanguage.En -> "${minutes}m ago"
            }
        }
        val hours = minutes / 60L
        if (hours < 24L) {
            return when (language) {
                ConnectionLanguage.Zh -> "${hours} 小时前"
                ConnectionLanguage.En -> "${hours}h ago"
            }
        }
        val days = hours / 24L
        return when (language) {
            ConnectionLanguage.Zh -> "${days} 天前"
            ConnectionLanguage.En -> "${days}d ago"
        }
    }

    fun status(status: JobStatus): HistoryStatusPresentation = when (status) {
        JobStatus.QUEUED -> HistoryStatusPresentation(
            symbol = "...",
            label = HistoryCopy.queued,
            tone = HistoryStatusTone.Running,
        )
        JobStatus.RUNNING -> HistoryStatusPresentation(
            symbol = "...",
            label = HistoryCopy.processing,
            tone = HistoryStatusTone.Running,
        )
        JobStatus.SUCCEEDED -> HistoryStatusPresentation(
            symbol = "OK",
            label = HistoryCopy.succeeded,
            tone = HistoryStatusTone.Success,
        )
        JobStatus.FAILED -> HistoryStatusPresentation(
            symbol = "!",
            label = HistoryCopy.failed,
            tone = HistoryStatusTone.Error,
        )
        JobStatus.INTERRUPTED -> HistoryStatusPresentation(
            symbol = "X",
            label = HistoryCopy.interrupted,
            tone = HistoryStatusTone.Neutral,
        )
    }

    private fun Job.matches(filter: HistoryFilter): Boolean = when (filter) {
        HistoryFilter.All -> true
        HistoryFilter.Successful -> status == JobStatus.SUCCEEDED
        HistoryFilter.Running -> status == JobStatus.QUEUED || status == JobStatus.RUNNING
        HistoryFilter.FailedCancelled -> status == JobStatus.FAILED || status == JobStatus.INTERRUPTED
    }

    private fun firstPromptText(element: JsonElement): String? = when (element) {
        is JsonObject -> {
            promptKeys.firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
            } ?: element.values.firstNotNullOfOrNull(::firstPromptText)
        }
        is JsonArray -> element.firstNotNullOfOrNull(::firstPromptText)
        else -> null
    }
}
