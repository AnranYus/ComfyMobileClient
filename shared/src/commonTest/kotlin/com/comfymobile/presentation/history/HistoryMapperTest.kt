package com.comfymobile.presentation.history

import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobStatus
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryMapperTest {

    @Test fun promptSnippet_extracts_first_clip_text_from_api_prompt() {
        val snippet = HistoryMapper.promptSnippet(
            apiPromptJson = """
                {
                  "3": {
                    "class_type": "KSampler",
                    "inputs": {"seed": 123}
                  },
                  "6": {
                    "class_type": "CLIPTextEncode",
                    "inputs": {"text": "a cinematic portrait with soft studio lighting"}
                  }
                }
            """.trimIndent(),
            language = ConnectionLanguage.En,
        )
        assertEquals("a cinematic portrait with soft studio lighting", snippet)
    }

    @Test fun promptSnippet_falls_back_when_api_prompt_missing_or_invalid() {
        assertEquals(
            "No prompt recorded",
            HistoryMapper.promptSnippet(apiPromptJson = "{", language = ConnectionLanguage.En),
        )
        assertEquals(
            "未记录提示词",
            HistoryMapper.promptSnippet(apiPromptJson = null, language = ConnectionLanguage.Zh),
        )
    }

    @Test fun rows_apply_status_filters_and_thumbnail_mapping() {
        val jobs = listOf(
            job("queued", JobStatus.QUEUED, createdAt = 4L),
            job("running", JobStatus.RUNNING, createdAt = 3L),
            job("success", JobStatus.SUCCEEDED, createdAt = 2L, output = JobOutputRef("a.png")),
            job("failed", JobStatus.FAILED, createdAt = 1L),
            job("cancelled", JobStatus.INTERRUPTED, createdAt = 0L),
        )
        val running = HistoryMapper.rows(
            jobs = jobs,
            selectedFilter = HistoryFilter.Running,
            language = ConnectionLanguage.En,
            nowEpochMs = 5_000L,
        )
        assertEquals(listOf("queued", "running"), running.map { it.promptId })

        val successful = HistoryMapper.rows(
            jobs = jobs,
            selectedFilter = HistoryFilter.Successful,
            language = ConnectionLanguage.En,
            nowEpochMs = 5_000L,
            thumbnailMapper = HistoryThumbnailMapper { output -> "thumb://${output.filename}" },
        )
        assertEquals(listOf("success"), successful.map { it.promptId })
        assertEquals("thumb://a.png", successful.single().thumbnailUrl)
    }

    @Test fun relativeTime_matches_spec_short_forms() {
        assertEquals("just now", HistoryMapper.relativeTime(1_000L, 15_000L, ConnectionLanguage.En))
        assertEquals("2m ago", HistoryMapper.relativeTime(0L, 120_000L, ConnectionLanguage.En))
        assertEquals("2 小时前", HistoryMapper.relativeTime(0L, 7_200_000L, ConnectionLanguage.Zh))
    }

    private fun job(
        promptId: String,
        status: JobStatus,
        createdAt: Long,
        output: JobOutputRef? = null,
    ): Job = Job(
        promptId = promptId,
        serverId = "srv-A",
        status = status,
        label = promptId,
        firstOutput = output,
        apiPromptJson = """{"1":{"inputs":{"text":"prompt for $promptId"}}}""",
        createdAtEpochMs = createdAt,
    )
}
