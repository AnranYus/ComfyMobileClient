package com.comfymobile.domain.node

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * The 10 control types the mobile UI knows how to render for a
 * whitelisted node parameter, plus 3 presentation-layer variants
 * (Integer-with-stepper / Seed special with 🎲🔒 / Prompt rich
 * autocomplete) that are NOT in the schema — those are decided by UI
 * code based on param semantics, see `docs/ux/T0.4-...` §2.2.
 *
 * `source` lives inside the variants that need it (Dropdown,
 * ModelPicker, ImagePicker). It is intentionally not on
 * [ParamDescriptor] so that nonsensical combinations like
 * "Slider + source" cannot be expressed.
 *
 * For the canonical schema reference see
 * `docs/architecture/T0.1-comfyui-integration.md` §3.5 and
 * `docs/ux/T1.5-node-descriptors-v1.json`.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed interface ControlType {

    /** Plain numeric control without a min/max range; used rarely. */
    @Serializable
    @SerialName("Number")
    data object Number : ControlType

    /**
     * Whole-number control with optional bounds. Examples in v1:
     * `EmptyLatentImage.batch_size` (1..8), `KSampler.seed`
     * (unbounded; UI layer adds 🎲 random + 🔒 lock buttons).
     */
    @Serializable
    @SerialName("Integer")
    data class Integer(
        val min: Int? = null,
        val max: Int? = null,
    ) : ControlType

    /**
     * Continuous numeric control with mandatory min/max/step. Examples
     * in v1: `KSampler.cfg` (0..30, step 0.5),
     * `EmptyLatentImage.width/height` (64..2048, step 64), with
     * optional [presets] for one-tap common values.
     */
    @Serializable
    @SerialName("Slider")
    data class Slider(
        val min: Double,
        val max: Double,
        val step: Double,
        val presets: List<Preset> = emptyList(),
    ) : ControlType

    @Serializable
    @SerialName("Toggle")
    data object Toggle : ControlType

    @Serializable
    @SerialName("SingleLineText")
    data object SingleLineText : ControlType

    /**
     * Multi-line text. Used by `CLIPTextEncode.text`. The optional
     * [autocomplete] list names the token namespaces UI should suggest
     * (e.g. `["embedding", "lora"]` to enable embedding/LoRA token
     * pickers).
     */
    @Serializable
    @SerialName("MultilineText")
    data class MultilineText(
        val autocomplete: List<String> = emptyList(),
    ) : ControlType

    /**
     * Single-select from server-supplied enum. Source is mandatory.
     * Used for `KSampler.sampler_name` / `scheduler` etc.
     */
    @Serializable
    @SerialName("Dropdown")
    data class Dropdown(val source: Source) : ControlType

    /**
     * Single-select from a ComfyUI model folder
     * (`/models/{folder}`). Used for `CheckpointLoaderSimple.ckpt_name`,
     * `LoraLoader.lora_name`, `ControlNetLoader.control_net_name`.
     */
    @Serializable
    @SerialName("ModelPicker")
    data class ModelPicker(val source: Source) : ControlType

    /**
     * Image upload / picker. Reserved for v2 (`LoadImage` is read-only
     * in v1).
     */
    @Serializable
    @SerialName("ImagePicker")
    data class ImagePicker(val source: Source) : ControlType

    /**
     * Param exists in the descriptor but is intentionally hidden from
     * mobile UI. The value is preserved verbatim through round-trip.
     */
    @Serializable
    @SerialName("Hidden")
    data object Hidden : ControlType
}

/**
 * A shortcut value for a Slider. Optional [label] lets the UI render
 * "Quick / Default / High" buttons; without label the preset shows
 * the bare number.
 *
 * `value` is JsonElement so the same Preset shape works for integer
 * (`{"value": 20}`), float (`{"value": 0.5}`), or future literal
 * preset types without changing the schema.
 */
@Serializable
data class Preset(
    val label: String? = null,
    val value: JsonElement,
)
