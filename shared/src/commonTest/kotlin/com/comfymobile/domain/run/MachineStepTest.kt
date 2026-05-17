package com.comfymobile.domain.run

import com.comfymobile.data.network.WsEvent
import com.comfymobile.domain.job.JobOutputRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure-data tests for [MachineStep]. No coroutines, no IO; covers every
 * transition + every "ignore" branch (other promptId, control event,
 * unknown event). Mirrors the @Lily seam discipline established for
 * Gesture/Viewport reducers in T2.1b.
 */
class MachineStepTest {

    private val pid = "p-123"
    private val other = "p-other"
    private val initial = MachineSnapshot(promptId = pid)

    // --- filtering -----------------------------------------------------------

    @Test fun status_event_is_ignored() {
        val next = MachineStep.step(initial, WsEvent.Status(queueRemaining = 0, sid = null))
        assertNull(next)
    }

    @Test fun feature_flags_event_is_ignored() {
        val next = MachineStep.step(initial, WsEvent.FeatureFlags(flags = buildJsonObject {}))
        assertNull(next)
    }

    @Test fun unknown_event_is_ignored() {
        val next = MachineStep.step(initial, WsEvent.Unknown(type = "preview", payload = buildJsonObject {}))
        assertNull(next)
    }

    @Test fun event_for_different_promptId_is_ignored() {
        val next = MachineStep.step(initial, WsEvent.ExecutionStart(promptId = other))
        assertNull(next)
    }

    // --- ExecutionStart / Executing -----------------------------------------

    @Test fun execution_start_clears_current_node_pointers() {
        val withNode = initial.copy(
            currentNodeId = "leftover",
            currentNodeDisplayName = "leftover display",
        )
        val next = MachineStep.step(withNode, WsEvent.ExecutionStart(pid))!!
        assertEquals(null, next.currentNodeId)
        assertEquals(null, next.currentNodeDisplayName)
        assertNull(next.terminal)
    }

    @Test fun executing_sets_current_node_and_display_name() {
        val next = MachineStep.step(
            initial,
            WsEvent.Executing(promptId = pid, node = "n-7", displayNode = "KSampler"),
        )!!
        assertEquals("n-7", next.currentNodeId)
        assertEquals("KSampler", next.currentNodeDisplayName)
    }

    @Test fun executing_null_node_is_end_of_run_sentinel_clears_pointers() {
        val mid = initial.copy(currentNodeId = "n-3", currentNodeDisplayName = "VAEDecode")
        val next = MachineStep.step(mid, WsEvent.Executing(promptId = pid, node = null))!!
        assertEquals(null, next.currentNodeId)
        assertEquals(null, next.currentNodeDisplayName)
        // No terminal yet — execution_success follows separately.
        assertNull(next.terminal)
    }

    // --- ExecutionCached -----------------------------------------------------

    @Test fun execution_cached_unions_into_cachedNodes() {
        val mid = initial.copy(cachedNodes = setOf("n-1"))
        val next = MachineStep.step(
            mid,
            WsEvent.ExecutionCached(promptId = pid, nodes = listOf("n-2", "n-3", "n-1")),
        )!!
        assertEquals(setOf("n-1", "n-2", "n-3"), next.cachedNodes)
    }

    // --- Progress ------------------------------------------------------------

    @Test fun progress_replaces_node_progress_with_latest() {
        val mid = initial.copy(
            nodeProgress = RunState.NodeProgress(nodeId = "n-old", value = 1, max = 10),
        )
        val next = MachineStep.step(
            mid,
            WsEvent.Progress(promptId = pid, node = "n-new", value = 5, max = 20),
        )!!
        assertEquals("n-new", next.nodeProgress?.nodeId)
        assertEquals(5, next.nodeProgress?.value)
        assertEquals(20, next.nodeProgress?.max)
    }

    @Test fun progress_state_passes_through_without_mutation() {
        val mid = initial.copy(currentNodeId = "n-2")
        val next = MachineStep.step(
            mid,
            WsEvent.ProgressState(promptId = pid, nodes = buildJsonObject {}),
        )!!
        assertEquals(mid, next)
    }

    // --- Executed ------------------------------------------------------------

    @Test fun executed_adds_node_to_completed_set() {
        val next = MachineStep.step(
            initial,
            WsEvent.Executed(
                promptId = pid,
                node = "n-9",
                output = buildJsonObject {},
            ),
        )!!
        assertEquals(setOf("n-9"), next.completedNodes)
    }

    @Test fun executed_clears_in_node_progress_because_node_finished() {
        val mid = initial.copy(
            nodeProgress = RunState.NodeProgress(nodeId = "n-9", value = 7, max = 10),
        )
        val next = MachineStep.step(
            mid,
            WsEvent.Executed(promptId = pid, node = "n-9", output = buildJsonObject {}),
        )!!
        assertNull(next.nodeProgress)
    }

    @Test fun executed_extracts_images_into_outputs_and_firstOutput() {
        val next = MachineStep.step(
            initial,
            WsEvent.Executed(
                promptId = pid,
                node = "n-save",
                output = buildJsonObject {
                    put("images", buildJsonArray {
                        add(buildJsonObject {
                            put("filename", JsonPrimitive("img_0001.png"))
                            put("subfolder", JsonPrimitive(""))
                            put("type", JsonPrimitive("output"))
                        })
                        add(buildJsonObject {
                            put("filename", JsonPrimitive("img_0002.png"))
                            put("subfolder", JsonPrimitive("batch_1"))
                            put("type", JsonPrimitive("output"))
                        })
                    })
                },
            ),
        )!!
        assertEquals(2, next.outputs.size)
        assertEquals(JobOutputRef("img_0001.png", "", "output"), next.outputs[0])
        assertEquals(JobOutputRef("img_0002.png", "batch_1", "output"), next.outputs[1])
        // firstOutput is the first image seen across the whole run.
        assertEquals(JobOutputRef("img_0001.png", "", "output"), next.firstOutput)
    }

    @Test fun executed_does_not_overwrite_firstOutput_once_set() {
        // Seed both firstOutput AND outputs to model the post-first-Executed
        // state correctly — firstOutput would never be set without the
        // corresponding entry having been appended to outputs.
        val firstImage = JobOutputRef("first.png", "", "output")
        val mid = initial.copy(
            firstOutput = firstImage,
            outputs = listOf(firstImage),
        )
        val next = MachineStep.step(
            mid,
            WsEvent.Executed(
                promptId = pid,
                node = "n-save-2",
                output = buildJsonObject {
                    put("images", buildJsonArray {
                        add(buildJsonObject {
                            put("filename", JsonPrimitive("second.png"))
                            put("subfolder", JsonPrimitive(""))
                            put("type", JsonPrimitive("output"))
                        })
                    })
                },
            ),
        )!!
        // firstOutput stays anchored to "first.png" even after a later node also emits images.
        assertEquals(firstImage, next.firstOutput)
        // but the new image is still appended to outputs.
        assertEquals(2, next.outputs.size)
        assertEquals(firstImage, next.outputs[0])
        assertEquals(JobOutputRef("second.png", "", "output"), next.outputs[1])
    }

    @Test fun executed_with_non_image_output_is_recorded_but_no_outputs_added() {
        val next = MachineStep.step(
            initial,
            WsEvent.Executed(
                promptId = pid,
                node = "n-text",
                output = buildJsonObject {
                    put("text", buildJsonArray { add(JsonPrimitive("ignored")) })
                },
            ),
        )!!
        assertEquals(setOf("n-text"), next.completedNodes)
        assertEquals(emptyList<JobOutputRef>(), next.outputs)
        assertNull(next.firstOutput)
    }

    // --- Terminal transitions -----------------------------------------------

    @Test fun execution_success_sets_Terminal_Success() {
        val next = MachineStep.step(initial, WsEvent.ExecutionSuccess(promptId = pid))!!
        assertEquals(Terminal.Success, next.terminal)
    }

    @Test fun execution_error_sets_Terminal_Failure_with_NodeException_payload() {
        val event = WsEvent.ExecutionError(
            promptId = pid,
            nodeId = "n-5",
            nodeType = "KSampler",
            executed = listOf("n-1", "n-2"),
            exceptionMessage = "out of memory",
            exceptionType = "torch.cuda.OutOfMemoryError",
            traceback = buildJsonArray { add(JsonPrimitive("Traceback…")) },
        )
        val next = MachineStep.step(initial, event)!!
        val terminal = next.terminal as Terminal.Failure
        val err = terminal.error as RunError.NodeException
        assertEquals("n-5", err.nodeId)
        assertEquals("KSampler", err.nodeType)
        assertEquals("out of memory", err.exceptionMessage)
        assertEquals("torch.cuda.OutOfMemoryError", err.exceptionType)
    }

    @Test fun execution_interrupted_sets_Terminal_Cancelled() {
        val next = MachineStep.step(
            initial,
            WsEvent.ExecutionInterrupted(promptId = pid, nodeId = "n-3"),
        )!!
        val terminal = next.terminal as Terminal.Cancelled
        assertEquals("n-3", terminal.fromNodeId)
    }

    // --- extractImageOutputs -------------------------------------------------

    @Test fun extractImageOutputs_returns_empty_for_non_object() {
        val result = MachineStep.extractImageOutputs(JsonArray(emptyList()))
        assertEquals(emptyList<JobOutputRef>(), result)
    }

    @Test fun extractImageOutputs_skips_entries_missing_filename() {
        val payload = buildJsonObject {
            put("images", buildJsonArray {
                add(buildJsonObject {
                    put("subfolder", JsonPrimitive(""))
                    put("type", JsonPrimitive("output"))
                })
                add(buildJsonObject {
                    put("filename", JsonPrimitive("good.png"))
                })
            })
        }
        val result = MachineStep.extractImageOutputs(payload)
        assertEquals(1, result.size)
        assertEquals("good.png", result[0].filename)
        // missing subfolder → ""; missing type → "output"
        assertEquals("", result[0].subfolder)
        assertEquals("output", result[0].type)
    }

    @Test fun extractImageOutputs_also_handles_gifs_array() {
        val payload = buildJsonObject {
            put("gifs", buildJsonArray {
                add(buildJsonObject {
                    put("filename", JsonPrimitive("animate.gif"))
                    put("subfolder", JsonPrimitive(""))
                    put("type", JsonPrimitive("output"))
                })
            })
        }
        val result = MachineStep.extractImageOutputs(payload)
        assertEquals(1, result.size)
        assertEquals("animate.gif", result[0].filename)
    }
}
