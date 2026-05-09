package com.comfymobile.data.descriptor

import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.NodeDescriptor
import com.comfymobile.domain.node.NodeDescriptorsFile
import com.comfymobile.domain.node.ParamDescriptor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses + validates a `node-descriptors/v1.json` payload.
 *
 * Pure function — no IO. Pull bytes from wherever (test resource,
 * Compose Multiplatform `Res.readBytes`, network…) and feed the
 * decoded UTF-8 string in. Any failure becomes
 * [InvalidDescriptorException] with a stable `path` so tests can
 * assert on the exact failure location.
 *
 * Validation rules applied (in order):
 *  1. JSON is well-formed and matches [NodeDescriptorsFile] schema.
 *  2. `version` is 1.
 *  3. No duplicate `classType` entries.
 *  4. Each [ControlType.Slider] has finite `min < max`, `step > 0`,
 *     and any preset value is a number inside `[min, max]`.
 *  5. Each [ControlType.Integer] with bounds has `min <= max`.
 *  6. Each Source-bearing control (Dropdown / ModelPicker /
 *     ImagePicker) carries a non-empty source.
 */
object NodeDescriptorParser {

    /** Lenient by intent — extra unknown fields in the JSON should
     * NOT break parsing, so authors can add forward-compat metadata. */
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type" // overridden per sealed type via @JsonClassDiscriminator
        isLenient = false
    }

    fun parse(jsonText: String): NodeDescriptorsFile {
        val file = try {
            json.decodeFromString(NodeDescriptorsFile.serializer(), jsonText)
        } catch (e: SerializationException) {
            throw InvalidDescriptorException(
                path = "(root)",
                reason = "JSON does not match descriptor schema: ${e.message}",
                cause = e,
            )
        }

        if (file.version != SUPPORTED_VERSION) {
            throw InvalidDescriptorException(
                path = "version",
                reason = "Unsupported descriptor version ${file.version}; this build understands $SUPPORTED_VERSION",
            )
        }

        validate(file)
        return file
    }

    private fun validate(file: NodeDescriptorsFile) {
        val seenClassTypes = mutableSetOf<String>()
        file.descriptors.forEachIndexed { index, descriptor ->
            val descriptorPath = "descriptors[${descriptor.classType.ifEmpty { "[$index]" }}]"

            if (descriptor.classType.isEmpty()) {
                throw InvalidDescriptorException(
                    path = "descriptors[$index].classType",
                    reason = "classType must be a non-empty string",
                )
            }
            if (!seenClassTypes.add(descriptor.classType)) {
                throw InvalidDescriptorException(
                    path = "$descriptorPath.classType",
                    reason = "duplicate classType '${descriptor.classType}'",
                )
            }
            validateDescriptor(descriptor, descriptorPath)
        }
    }

    private fun validateDescriptor(descriptor: NodeDescriptor, basePath: String) {
        val seenParamNames = mutableSetOf<String>()
        descriptor.editableParams.forEachIndexed { index, param ->
            val paramPath = "$basePath.editableParams[${param.name.ifEmpty { "[$index]" }}]"
            if (param.name.isEmpty()) {
                throw InvalidDescriptorException(
                    path = "$basePath.editableParams[$index].name",
                    reason = "param name must be a non-empty string",
                )
            }
            if (!seenParamNames.add(param.name)) {
                throw InvalidDescriptorException(
                    path = "$paramPath.name",
                    reason = "duplicate param name '${param.name}'",
                )
            }
            validateControl(param, paramPath)
        }
    }

    private fun validateControl(param: ParamDescriptor, paramPath: String) {
        when (val c = param.control) {
            is ControlType.Slider -> {
                val controlPath = "$paramPath.control"
                if (!c.min.isFinite() || !c.max.isFinite()) {
                    throw InvalidDescriptorException(
                        path = "$controlPath.{min|max}",
                        reason = "Slider min/max must be finite numbers",
                    )
                }
                if (c.min >= c.max) {
                    throw InvalidDescriptorException(
                        path = controlPath,
                        reason = "Slider min (${c.min}) must be < max (${c.max})",
                    )
                }
                if (c.step <= 0) {
                    throw InvalidDescriptorException(
                        path = "$controlPath.step",
                        reason = "Slider step must be positive (got ${c.step})",
                    )
                }
                c.presets.forEachIndexed { i, preset ->
                    val presetPath = "$controlPath.presets[$i].value"
                    val numeric = preset.value.numericOrNull()
                        ?: throw InvalidDescriptorException(
                            path = presetPath,
                            reason = "preset value must be a numeric JSON primitive",
                        )
                    if (numeric < c.min || numeric > c.max) {
                        throw InvalidDescriptorException(
                            path = presetPath,
                            reason = "preset $numeric is outside Slider range [${c.min}, ${c.max}]",
                        )
                    }
                }
            }
            is ControlType.Integer -> {
                val mn = c.min
                val mx = c.max
                if (mn != null && mx != null && mn > mx) {
                    throw InvalidDescriptorException(
                        path = "$paramPath.control",
                        reason = "Integer min ($mn) must be <= max ($mx)",
                    )
                }
            }
            else -> Unit // ControlType.Dropdown / ModelPicker / ImagePicker carry mandatory `source` — already enforced by sealed class shape; static / parameterless variants need no further check.
        }
    }

    private const val SUPPORTED_VERSION = 1

    /**
     * Accepts JSON numeric primitives (integers and floats) and
     * returns their Double value; rejects strings, booleans, null,
     * arrays, and objects.
     */
    private fun JsonElement.numericOrNull(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()
    }
}
