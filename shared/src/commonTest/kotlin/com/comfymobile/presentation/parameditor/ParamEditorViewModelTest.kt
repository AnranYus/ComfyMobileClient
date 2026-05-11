package com.comfymobile.presentation.parameditor

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ParamEditorViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun open_loads_source_options_through_seam() = runTest {
        val vm = viewModel(
            optionProvider = ParamOptionProvider { request ->
                assertEquals("sampler_name", request.paramName)
                Result.success(listOf(ParamOption("euler"), ParamOption("dpmpp_2m")))
            },
            scope = this,
        )

        vm.open(workflow(), "1")
        runCurrent()

        val editor = vm.state.value.editor!!
        val sampler = editor.rows.first { it.param.name == "sampler_name" }
        val options = assertIs<ParamOptionsState.Loaded>(sampler.options)
        assertEquals(listOf("euler", "dpmpp_2m"), options.options.map { it.value })
    }

    @Test fun preview_edits_do_not_apply_until_apply_action() = runTest {
        val vm = viewModel(optionProvider = EmptyParamOptionProvider, scope = this)
        val original = workflow()

        vm.open(original, "1")
        vm.onTextChanged("steps", "42")
        runCurrent()

        assertNull(vm.state.value.lastAppliedEnvelope)
        vm.onApply()

        val applied = vm.state.value.lastAppliedEnvelope!!
        val node = applied.original
            .let { it as kotlinx.serialization.json.JsonObject }
            .getValue("nodes")
            .jsonArray
            .single()
            .jsonObject
        assertEquals("42", node.getValue("widgets_values").jsonArray[2].jsonPrimitive.content)
    }

    @Test fun dirty_dismiss_requires_confirmation() = runTest {
        val vm = viewModel(optionProvider = EmptyParamOptionProvider, scope = this)

        vm.open(workflow(), "1")
        vm.onTextChanged("steps", "42")
        vm.onDismissRequested()

        assertEquals(true, vm.state.value.editor?.confirmDiscardVisible)
        vm.onDiscard()
        assertNull(vm.state.value.editor)
    }

    @Test fun reset_returns_values_to_drawer_open_snapshot() = runTest {
        val vm = viewModel(optionProvider = EmptyParamOptionProvider, scope = this)

        vm.open(workflow(), "1")
        vm.onTextChanged("steps", "42")
        assertEquals(true, vm.state.value.editor?.isDirty)

        vm.onReset()

        val steps = vm.state.value.editor!!.rows.first { it.param.name == "steps" }
        assertEquals("20", (steps.value as ParamDraftValue.NumberText).value)
        assertEquals(false, vm.state.value.editor!!.isDirty)
    }

    @Test fun stale_option_result_after_switching_nodes_is_ignored() = runTest {
        val first = CompletableDeferred<Result<List<ParamOption>>>()
        val second = CompletableDeferred<Result<List<ParamOption>>>()
        var calls = 0
        val vm = viewModel(
            optionProvider = ParamOptionProvider {
                calls += 1
                if (calls == 1) first.await() else second.await()
            },
            scope = this,
        )

        vm.open(workflow(nodeId = 1), "1")
        runCurrent()
        vm.open(workflow(nodeId = 2), "2")
        runCurrent()

        first.complete(Result.success(listOf(ParamOption("stale-from-node-1"))))
        runCurrent()

        val afterStale = vm.state.value.editor!!.rows.first { it.param.name == "sampler_name" }
        assertIs<ParamOptionsState.Loading>(afterStale.options)

        second.complete(Result.success(listOf(ParamOption("fresh-from-node-2"))))
        runCurrent()

        val afterFresh = vm.state.value.editor!!.rows.first { it.param.name == "sampler_name" }
        val loaded = assertIs<ParamOptionsState.Loaded>(afterFresh.options)
        assertEquals(listOf("fresh-from-node-2"), loaded.options.map { it.value })
    }

    private fun viewModel(
        optionProvider: ParamOptionProvider,
        scope: CoroutineScope,
    ): ParamEditorViewModel =
        ParamEditorViewModel(
            registry = registry(),
            optionProvider = optionProvider,
            scope = scope,
            nowEpochMs = { 500L },
            nextSeed = { 123L },
        )

    private fun workflow(nodeId: Int = 1): WorkflowEnvelope =
        WorkflowEnvelope(
            original = json.parseToJsonElement(
                """
                {
                  "nodes": [
                    {
                      "id": $nodeId,
                      "type": "KSampler",
                      "widgets_values": [12345, "randomize", 20, 7.5, "euler", "normal", 1.0]
                    }
                  ],
                  "links": []
                }
                """.trimIndent(),
            ),
            format = WorkflowFormat.UI,
            metadata = WorkflowMetadata(
                label = "Workflow",
                createdAtEpochMs = 100L,
                lastEditedAtEpochMs = 100L,
            ),
        )

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
                    {"name":"sampler_name","displayName":{"zh":"采样算法","en":"Sampler"},"control":{"type":"Dropdown","source":{"kind":"NodeEnumFromObjectInfo","param":"sampler_name"}}}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
}
