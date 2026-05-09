package com.comfymobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Per @Lily's seam (msg `3466da2b`), inputs stay as raw JSON fixture
 * strings so QA can extend the matrix later without touching helper
 * code. All 9 execution events plus the 2 control events plus the
 * Unknown fallback have at least one passing case.
 */
class WsEventParserTest {

    @Test fun status_with_queue_remaining_and_sid() {
        val event = WsEventParser.parse(
            """{"type":"status","data":{"status":{"exec_info":{"queue_remaining":3}},"sid":"abc"}}"""
        )
        assertIs<WsEvent.Status>(event)
        assertEquals(3, event.queueRemaining)
        assertEquals("abc", event.sid)
    }

    @Test fun status_with_no_sid_defaults_to_null() {
        val event = WsEventParser.parse(
            """{"type":"status","data":{"status":{"exec_info":{"queue_remaining":0}}}}"""
        )
        assertIs<WsEvent.Status>(event)
        assertEquals(0, event.queueRemaining)
        assertNull(event.sid)
    }

    @Test fun status_missing_queue_remaining_defaults_to_0() {
        // Some ComfyUI builds emit `status` as a heartbeat without
        // an `exec_info` block. Treating that as queue=0 is the
        // intentional fallback (see WsEventParser.parseStatus).
        val event = WsEventParser.parse("""{"type":"status","data":{}}""")
        val status = assertIs<WsEvent.Status>(event)
        assertEquals(0, status.queueRemaining)
        assertNull(status.sid)
    }

    @Test fun status_with_status_block_but_missing_exec_info_defaults_to_0() {
        val event = WsEventParser.parse("""{"type":"status","data":{"status":{}}}""")
        val status = assertIs<WsEvent.Status>(event)
        assertEquals(0, status.queueRemaining)
    }

    @Test fun feature_flags_keeps_payload_verbatim() {
        val event = WsEventParser.parse(
            """{"type":"feature_flags","data":{"supports_preview_metadata":true,"version":3}}"""
        )
        val flags = assertIs<WsEvent.FeatureFlags>(event)
        // event.flags is already a JsonElement — no need to round-trip.
        val payload = flags.flags.jsonObject
        assertEquals(
            "true",
            payload["supports_preview_metadata"]?.jsonPrimitive?.content,
        )
    }

    @Test fun execution_start_extracts_prompt_id() {
        val event = WsEventParser.parse(
            """{"type":"execution_start","data":{"prompt_id":"p-1"}}"""
        )
        assertEquals(WsEvent.ExecutionStart(promptId = "p-1"), event)
    }

    @Test fun execution_cached_collects_node_id_array() {
        val event = WsEventParser.parse(
            """{"type":"execution_cached","data":{"prompt_id":"p-1","nodes":["3","4","5"]}}"""
        )
        assertEquals(
            WsEvent.ExecutionCached(promptId = "p-1", nodes = listOf("3", "4", "5")),
            event,
        )
    }

    @Test fun executing_with_node_string_value() {
        val event = WsEventParser.parse(
            """{"type":"executing","data":{"prompt_id":"p-1","node":"7","display_node":"7"}}"""
        )
        assertEquals(
            WsEvent.Executing(promptId = "p-1", node = "7", displayNode = "7"),
            event,
        )
    }

    @Test fun executing_with_node_null_means_idle_at_end() {
        val event = WsEventParser.parse(
            """{"type":"executing","data":{"prompt_id":"p-1","node":null}}"""
        )
        val executing = assertIs<WsEvent.Executing>(event)
        assertEquals("p-1", executing.promptId)
        assertNull(executing.node)
    }

    @Test fun progress_extracts_value_and_max() {
        val event = WsEventParser.parse(
            """{"type":"progress","data":{"prompt_id":"p-1","node":"3","value":7,"max":20}}"""
        )
        assertEquals(WsEvent.Progress(promptId = "p-1", node = "3", value = 7, max = 20), event)
    }

    @Test fun progress_state_keeps_nodes_payload_verbatim() {
        val event = WsEventParser.parse(
            """{"type":"progress_state","data":{"prompt_id":"p-1","nodes":{"3":{"value":7,"max":20,"state":"running"}}}}"""
        )
        val progressState = assertIs<WsEvent.ProgressState>(event)
        assertEquals("p-1", progressState.promptId)
        // payload preserved verbatim — not re-shaped
        val nodesObj = progressState.nodes.jsonObject
        assertNotNull(nodesObj["3"])
    }

    @Test fun executed_keeps_output_payload_verbatim() {
        val event = WsEventParser.parse(
            """{"type":"executed","data":{"prompt_id":"p-1","node":"9","output":{"images":[{"filename":"x.png","subfolder":"","type":"output"}]}}}"""
        )
        val executed = assertIs<WsEvent.Executed>(event)
        assertEquals("p-1", executed.promptId)
        assertEquals("9", executed.node)
        // output is a JsonElement; we don't decode it here.
        assertNotNull(executed.output.jsonObject["images"])
    }

    @Test fun execution_error_carries_full_diagnostic_payload() {
        val event = WsEventParser.parse(
            """
            {"type":"execution_error","data":{
              "prompt_id":"p-1","node_id":"3","node_type":"KSampler",
              "executed":["1","2"],"exception_message":"boom","exception_type":"RuntimeError",
              "traceback":["line1","line2"],"current_inputs":{"a":1},"current_outputs":{}
            }}
            """.trimIndent()
        )
        val err = assertIs<WsEvent.ExecutionError>(event)
        assertEquals("p-1", err.promptId)
        assertEquals("3", err.nodeId)
        assertEquals("KSampler", err.nodeType)
        assertEquals(listOf("1", "2"), err.executed)
        assertEquals("boom", err.exceptionMessage)
        assertEquals("RuntimeError", err.exceptionType)
        assertNotNull(err.traceback)
        assertNotNull(err.currentInputs)
        assertNotNull(err.currentOutputs)
    }

    @Test fun execution_interrupted_with_minimal_payload() {
        val event = WsEventParser.parse(
            """{"type":"execution_interrupted","data":{"prompt_id":"p-1"}}"""
        )
        val interrupted = assertIs<WsEvent.ExecutionInterrupted>(event)
        assertEquals("p-1", interrupted.promptId)
        assertNull(interrupted.nodeId)
        assertContentEquals(emptyList(), interrupted.executed)
    }

    @Test fun execution_success_extracts_prompt_id() {
        val event = WsEventParser.parse(
            """{"type":"execution_success","data":{"prompt_id":"p-1"}}"""
        )
        assertEquals(WsEvent.ExecutionSuccess(promptId = "p-1"), event)
    }

    // ------------------------------------------------------------------- fallbacks

    @Test fun unknown_event_type_returns_unknown_with_payload_intact() {
        val event = WsEventParser.parse(
            """{"type":"some_future_event","data":{"foo":"bar"}}"""
        )
        val unknown = assertIs<WsEvent.Unknown>(event)
        assertEquals("some_future_event", unknown.type)
    }

    @Test fun frame_missing_type_field_returns_unknown_missing_type() {
        val event = WsEventParser.parse("""{"data":{"foo":1}}""")
        val unknown = assertIs<WsEvent.Unknown>(event)
        assertEquals("(missing-type)", unknown.type)
    }

    @Test fun non_object_root_returns_unknown_non_object() {
        val event = WsEventParser.parse("""[1, 2, 3]""")
        val unknown = assertIs<WsEvent.Unknown>(event)
        assertEquals("(non-object)", unknown.type)
    }

    @Test fun execution_start_missing_prompt_id_falls_back_to_unknown() {
        val event = WsEventParser.parse("""{"type":"execution_start","data":{}}""")
        val unknown = assertIs<WsEvent.Unknown>(event)
        assertEquals("execution_start", unknown.type)
    }

    @Test fun progress_missing_value_falls_back_to_unknown() {
        val event = WsEventParser.parse(
            """{"type":"progress","data":{"prompt_id":"p-1","node":"3","max":20}}"""
        )
        val unknown = assertIs<WsEvent.Unknown>(event)
        assertEquals("progress", unknown.type)
    }

    @Test fun extra_unknown_keys_in_payload_are_tolerated() {
        // ComfyUI may add fields in future versions; we should not fail.
        val event = WsEventParser.parse(
            """{"type":"executing","data":{"prompt_id":"p-1","node":"3","extra_field":42}}"""
        )
        val executing = assertIs<WsEvent.Executing>(event)
        assertEquals("p-1", executing.promptId)
        assertEquals("3", executing.node)
    }

    @Test fun parse_via_jsonElement_overload_yields_same_result() {
        val text = """{"type":"executing","data":{"prompt_id":"p-1","node":"3"}}"""
        val fromText = WsEventParser.parse(text)
        val element = Json.Default.parseToJsonElement(text)
        val fromElement = WsEventParser.parse(element)
        assertEquals(fromText, fromElement)
    }
}
