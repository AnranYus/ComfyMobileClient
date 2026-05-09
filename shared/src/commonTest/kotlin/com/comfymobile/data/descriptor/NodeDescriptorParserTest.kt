package com.comfymobile.data.descriptor

import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.Source
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [NodeDescriptorParser] + [NodeDescriptorRegistry].
 *
 * Per @Lily's review (msg 9ca64274), every schema/semantic failure
 * must surface as [InvalidDescriptorException] with a stable `path`.
 * Tests assert on that path so the contract holds across kotlinx-
 * serialization version bumps.
 *
 * Fixtures are inline JSON strings — keeping resource IO out of
 * commonTest avoids platform-specific test glue for Phase 1.
 */
class NodeDescriptorParserTest {

    // ---------------------------------------------------------------- happy path

    @Test
    fun parses_minimal_valid_v1_with_one_descriptor() {
        val json = """
            {
              "version": 1,
              "descriptors": [
                {
                  "classType": "CheckpointLoaderSimple",
                  "displayName": { "zh": "加载基础模型", "en": "Load Checkpoint" },
                  "category": "loader",
                  "editableParams": [
                    {
                      "name": "ckpt_name",
                      "displayName": { "zh": "模型", "en": "Checkpoint" },
                      "control": {
                        "type": "ModelPicker",
                        "source": { "kind": "ModelFolder", "folder": "checkpoints" }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val registry = NodeDescriptorRegistry.fromJson(json)
        assertEquals(1, registry.version)
        assertEquals(1, registry.size)
        val ckpt = registry.lookup("CheckpointLoaderSimple")
        assertNotNull(ckpt)
        assertEquals("loader", ckpt.category)
        assertEquals(1, ckpt.editableParams.size)
        val ctrl = ckpt.editableParams.single().control
        assertTrue(ctrl is ControlType.ModelPicker)
        assertEquals(Source.ModelFolder("checkpoints"), ctrl.source)
    }

    @Test
    fun parses_all_ten_control_types_in_one_descriptor_set() {
        // Synthetic descriptor exercising every ControlType variant.
        // Each control type maps to a unique node id so we can
        // round-trip and verify shape.
        val json = """
            {
              "version": 1,
              "descriptors": [
                {
                  "classType": "AllControlsTest",
                  "displayName": { "zh": "全控件" },
                  "category": "test",
                  "editableParams": [
                    { "name": "a", "displayName": { "zh": "Number" },
                      "control": { "type": "Number" } },
                    { "name": "b", "displayName": { "zh": "Integer" },
                      "control": { "type": "Integer", "min": 1, "max": 16 } },
                    { "name": "c", "displayName": { "zh": "Slider" },
                      "control": {
                        "type": "Slider",
                        "min": 0, "max": 1, "step": 0.05,
                        "presets": [ { "label": "Mid", "value": 0.5 }, { "value": 0.75 } ]
                      } },
                    { "name": "d", "displayName": { "zh": "Toggle" },
                      "control": { "type": "Toggle" } },
                    { "name": "e", "displayName": { "zh": "SingleLineText" },
                      "control": { "type": "SingleLineText" } },
                    { "name": "f", "displayName": { "zh": "MultilineText" },
                      "control": { "type": "MultilineText", "autocomplete": ["embedding", "lora"] } },
                    { "name": "g", "displayName": { "zh": "Dropdown" },
                      "control": {
                        "type": "Dropdown",
                        "source": { "kind": "NodeEnumFromObjectInfo", "param": "sampler_name" }
                      } },
                    { "name": "h", "displayName": { "zh": "ModelPicker" },
                      "control": {
                        "type": "ModelPicker",
                        "source": { "kind": "ModelFolder", "folder": "loras" }
                      } },
                    { "name": "i", "displayName": { "zh": "ImagePicker" },
                      "control": {
                        "type": "ImagePicker",
                        "source": { "kind": "EmbeddingsList" }
                      } },
                    { "name": "j", "displayName": { "zh": "Hidden" },
                      "control": { "type": "Hidden" } }
                  ]
                }
              ]
            }
        """.trimIndent()

        val registry = NodeDescriptorRegistry.fromJson(json)
        val descriptor = registry.lookup("AllControlsTest")
        assertNotNull(descriptor)
        assertEquals(10, descriptor.editableParams.size)

        val byName = descriptor.editableParams.associateBy { it.name }
        assertTrue(byName["a"]!!.control is ControlType.Number)
        val intCtrl = byName["b"]!!.control as ControlType.Integer
        assertEquals(1, intCtrl.min)
        assertEquals(16, intCtrl.max)
        val slider = byName["c"]!!.control as ControlType.Slider
        assertEquals(0.0, slider.min)
        assertEquals(1.0, slider.max)
        assertEquals(0.05, slider.step)
        assertEquals(2, slider.presets.size)
        assertEquals("Mid", slider.presets[0].label)
        assertNull(slider.presets[1].label)
        assertTrue(byName["d"]!!.control is ControlType.Toggle)
        assertTrue(byName["e"]!!.control is ControlType.SingleLineText)
        val multi = byName["f"]!!.control as ControlType.MultilineText
        assertEquals(listOf("embedding", "lora"), multi.autocomplete)
        val dropdown = byName["g"]!!.control as ControlType.Dropdown
        assertEquals(Source.NodeEnumFromObjectInfo("sampler_name"), dropdown.source)
        val mp = byName["h"]!!.control as ControlType.ModelPicker
        assertEquals(Source.ModelFolder("loras"), mp.source)
        val ip = byName["i"]!!.control as ControlType.ImagePicker
        assertEquals(Source.EmbeddingsList, ip.source)
        assertTrue(byName["j"]!!.control is ControlType.Hidden)
    }

    @Test
    fun lookup_unknown_classType_returns_null_without_error() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [] } ] }
        """.trimIndent()
        val registry = NodeDescriptorRegistry.fromJson(json)
        assertNull(registry.lookup("ApplyAControlNetThatDoesNotExist"))
    }

    @Test
    fun forward_compat_unknown_top_level_field_does_not_break_parse() {
        // A future v1 might add `metadata` at top level; current
        // loader should ignore it.
        val json = """
            {
              "version": 1,
              "metadata": { "generatedAt": "2026-05-10" },
              "descriptors": [
                { "classType": "VAEDecode",
                  "displayName": { "zh": "VAE 解码" },
                  "category": "processing",
                  "editableParams": [] }
              ]
            }
        """.trimIndent()
        val registry = NodeDescriptorRegistry.fromJson(json)
        assertEquals(1, registry.size)
    }

    // ---------------------------------------------------------------- failure paths

    @Test
    fun unsupported_version_fails_with_stable_path() {
        val json = """{ "version": 99, "descriptors": [] }"""
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("version", ex.path)
        assertContains(ex.message ?: "", "99")
    }

    @Test
    fun malformed_json_fails_with_root_path() {
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson("not valid json {{{")
        }
        assertEquals("(root)", ex.path)
    }

    @Test
    fun missing_classType_fails_with_descriptor_index_path() {
        // Empty classType is treated as "missing" because Kotlin
        // serialization will deserialize JSON null → empty string with
        // our default; we explicitly reject the empty-string case.
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "",
                "displayName": { "zh": "x" },
                "category": "test",
                "editableParams": [] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[0].classType", ex.path)
    }

    @Test
    fun duplicate_classType_fails_with_descriptor_path() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "a" },
                "category": "sampler", "editableParams": [] },
              { "classType": "KSampler", "displayName": { "zh": "b" },
                "category": "sampler", "editableParams": [] }
            ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[KSampler].classType", ex.path)
    }

    @Test
    fun slider_with_min_greater_than_max_fails_with_control_path() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [
                  { "name": "cfg", "displayName": { "zh": "CFG" },
                    "control": { "type": "Slider", "min": 30, "max": 0, "step": 0.5 } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[KSampler].editableParams[cfg].control", ex.path)
        assertContains(ex.message ?: "", "min")
        assertContains(ex.message ?: "", "max")
    }

    @Test
    fun slider_with_zero_step_fails_with_step_path() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [
                  { "name": "cfg", "displayName": { "zh": "CFG" },
                    "control": { "type": "Slider", "min": 0, "max": 30, "step": 0 } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[KSampler].editableParams[cfg].control.step", ex.path)
    }

    @Test
    fun slider_preset_outside_range_fails_with_preset_path() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [
                  { "name": "cfg", "displayName": { "zh": "CFG" },
                    "control": { "type": "Slider", "min": 0, "max": 1, "step": 0.1,
                      "presets": [ { "value": 5 } ] } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[KSampler].editableParams[cfg].control.presets[0].value", ex.path)
    }

    @Test
    fun integer_with_min_greater_than_max_fails_with_control_path() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "EmptyLatentImage", "displayName": { "zh": "空白画布" },
                "category": "input", "editableParams": [
                  { "name": "batch_size", "displayName": { "zh": "批量" },
                    "control": { "type": "Integer", "min": 8, "max": 1 } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[EmptyLatentImage].editableParams[batch_size].control", ex.path)
    }

    @Test
    fun unknown_control_type_fails_root_with_clear_reason() {
        // kotlinx.serialization rejects unknown @SerialName; we wrap.
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [
                  { "name": "weird", "displayName": { "zh": "怪" },
                    "control": { "type": "MysteryWidget" } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        // We can't pin the exact path through kotlinx-serialization,
        // but we can at least guarantee the wrapping happened.
        assertEquals("(root)", ex.path)
        assertContains(ex.message ?: "", "schema")
    }

    @Test
    fun unknown_source_kind_fails_root_with_clear_reason() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [
                  { "name": "sampler_name", "displayName": { "zh": "采样算法" },
                    "control": { "type": "Dropdown", "source": { "kind": "TotallyMadeUp" } } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("(root)", ex.path)
    }

    @Test
    fun duplicate_param_name_in_descriptor_fails_with_param_path() {
        val json = """
            { "version": 1, "descriptors": [
              { "classType": "KSampler", "displayName": { "zh": "采样器" },
                "category": "sampler", "editableParams": [
                  { "name": "seed", "displayName": { "zh": "种子" },
                    "control": { "type": "Integer" } },
                  { "name": "seed", "displayName": { "zh": "种子 2" },
                    "control": { "type": "Integer" } }
                ] } ] }
        """.trimIndent()
        val ex = assertFailsWith<InvalidDescriptorException> {
            NodeDescriptorRegistry.fromJson(json)
        }
        assertEquals("descriptors[KSampler].editableParams[seed].name", ex.path)
    }
}
