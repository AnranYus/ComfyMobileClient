package com.comfymobile.presentation.parameditor

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParamEditorCoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun open_uses_comfy_widget_order_not_descriptor_index() {
        val state = openKSampler().state

        assertEquals("12345", state.row("seed").numberText)
        assertEquals("20", state.row("steps").numberText)
        assertEquals("7.5", state.row("cfg").numberText)
        assertEquals("euler", state.row("sampler_name").text)
    }

    @Test fun apply_patches_widgets_values_and_preserves_ui_only_slots() {
        val envelope = kSamplerEnvelope()
        val state = openKSampler(envelope).state
            .let { ParamEditorCore.updateValue(it, "steps", ParamDraftValue.NumberText("30")) }
            .let { ParamEditorCore.updateValue(it, "cfg", ParamDraftValue.NumberText("8.5")) }

        val result = assertIs<ParamEditorApplyResult.Applied>(
            ParamEditorCore.apply(envelope, state, nowEpochMs = 200L),
        )
        val widgets = widgetsValues(result.envelope)

        assertEquals(JsonPrimitive(12345), widgets[0])
        assertEquals(JsonPrimitive("randomize"), widgets[1])
        assertEquals(JsonPrimitive(30), widgets[2])
        assertEquals(8.5, widgets[3].jsonPrimitive.doubleOrNull)
        assertEquals(200L, result.envelope.metadata.lastEditedAtEpochMs)
    }

    @Test fun invalid_slider_bounds_blocks_apply() {
        val envelope = kSamplerEnvelope()
        val state = openKSampler(envelope).state
            .let { ParamEditorCore.updateValue(it, "cfg", ParamDraftValue.NumberText("31")) }

        val result = assertIs<ParamEditorApplyResult.Invalid>(
            ParamEditorCore.apply(envelope, state, nowEpochMs = 200L),
        )

        assertEquals("Must be at most 30", result.errors.single().en)
    }

    @Test fun read_only_node_does_not_open_drawer() {
        val result = ParamEditorCore.open(
            envelope = unknownEnvelope(),
            nodeId = "99",
            registry = registry(),
        )

        assertEquals(ParamEditorOpenResult.ReadOnlyNode("CustomNode"), result)
    }

    @Test fun hidden_param_is_not_dirty_and_not_written() {
        val envelope = envelope(
            """
            {
              "nodes": [
                {"id":1,"type":"SecretNode","widgets_values":["keep","visible"]}
              ],
              "links": []
            }
            """.trimIndent(),
        )
        val registry = NodeDescriptorRegistry.fromJson(
            """
            {
              "version": 1,
              "descriptors": [
                {
                  "classType": "SecretNode",
                  "displayName": {"zh":"Secret","en":"Secret"},
                  "category": "test",
                  "editableParams": [
                    {"name":"internal","displayName":{"zh":"Internal","en":"Internal"},"control":{"type":"Hidden"}},
                    {"name":"visible","displayName":{"zh":"Visible","en":"Visible"},"control":{"type":"SingleLineText"}}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val opened = assertIs<ParamEditorOpenResult.Ready>(
            ParamEditorCore.open(envelope, "1", registry),
        ).state
        val edited = ParamEditorCore.updateValue(opened, "visible", ParamDraftValue.Text("updated"))
        val applied = assertIs<ParamEditorApplyResult.Applied>(
            ParamEditorCore.apply(envelope, edited, nowEpochMs = 300L),
        )

        val widgets = widgetsValues(applied.envelope)
        assertEquals(JsonPrimitive("keep"), widgets[0])
        assertEquals(JsonPrimitive("updated"), widgets[1])
    }

    @Test fun apply_without_dirty_change_returns_not_dirty() {
        val envelope = kSamplerEnvelope()
        val state = openKSampler(envelope).state

        assertEquals(
            ParamEditorApplyResult.NotDirty,
            ParamEditorCore.apply(envelope, state, nowEpochMs = 200L),
        )
    }

    private fun openKSampler(envelope: WorkflowEnvelope = kSamplerEnvelope()): ParamEditorOpenResult.Ready =
        assertIs<ParamEditorOpenResult.Ready>(
            ParamEditorCore.open(
                envelope = envelope,
                nodeId = "1",
                registry = registry(),
            )
        )

    private fun ParamEditorState.row(name: String): ParamEditorRowState =
        rows.first { it.param.name == name }

    private val ParamEditorRowState.numberText: String
        get() = (value as ParamDraftValue.NumberText).value

    private val ParamEditorRowState.text: String
        get() = (value as ParamDraftValue.Text).value

    private fun kSamplerEnvelope(): WorkflowEnvelope = envelope(
        """
        {
          "nodes": [
            {
              "id": 1,
              "type": "KSampler",
              "title": "Sampler",
              "widgets_values": [12345, "randomize", 20, 7.5, "euler", "normal", 1.0]
            }
          ],
          "links": []
        }
        """.trimIndent(),
    )

    private fun unknownEnvelope(): WorkflowEnvelope = envelope(
        """
        {
          "nodes": [
            {"id":99,"type":"CustomNode","widgets_values":[]}
          ],
          "links": []
        }
        """.trimIndent(),
    )

    private fun envelope(raw: String): WorkflowEnvelope =
        WorkflowEnvelope(
            original = json.parseToJsonElement(raw),
            format = WorkflowFormat.UI,
            metadata = WorkflowMetadata(
                label = "Workflow",
                createdAtEpochMs = 100L,
                lastEditedAtEpochMs = 100L,
            ),
        )

    private fun widgetsValues(envelope: WorkflowEnvelope): JsonArray {
        val raw = envelope.original as JsonObject
        val node = raw["nodes"]!!.jsonArray.single().jsonObject
        return node["widgets_values"]!!.jsonArray
    }

    private fun registry(): NodeDescriptorRegistry =
        NodeDescriptorRegistry.fromJson(
            """
            {
              "version": 1,
              "descriptors": [
                {
                  "classType": "KSampler",
                  "displayName": {"zh":"采样器","en":"Sampler"},
                  "category": "sampler",
                  "editableParams": [
                    {"name":"seed","displayName":{"zh":"随机种子","en":"Seed"},"control":{"type":"Integer"}},
                    {"name":"steps","displayName":{"zh":"步数","en":"Steps"},"control":{"type":"Slider","min":1,"max":150,"step":1}},
                    {"name":"cfg","displayName":{"zh":"提示词强度","en":"CFG"},"control":{"type":"Slider","min":0,"max":30,"step":0.5}},
                    {"name":"sampler_name","displayName":{"zh":"采样算法","en":"Sampler"},"control":{"type":"Dropdown","source":{"kind":"NodeEnumFromObjectInfo","param":"sampler_name"}}},
                    {"name":"scheduler","displayName":{"zh":"调度器","en":"Scheduler"},"control":{"type":"Dropdown","source":{"kind":"NodeEnumFromObjectInfo","param":"scheduler"}}},
                    {"name":"denoise","displayName":{"zh":"去噪强度","en":"Denoise"},"control":{"type":"Slider","min":0,"max":1,"step":0.05}}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
}
