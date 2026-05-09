package com.comfymobile.domain.node

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// Where a UI control gets its enumerable values from. Only meaningful
// for source-driven controls (Dropdown / ModelPicker / ImagePicker);
// other ControlType variants don't carry a Source.
//
// The values are NEVER baked at descriptor-author time — they always
// come from the live ComfyUI server (/object_info, /models/*,
// /embeddings).
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface Source {

    // Pull values from the named param's enum in
    // GET /object_info[classType].input.required[param]. Used for
    // server-defined enums like KSampler.sampler_name.
    //
    // param defaults to the descriptor's own param name; only specify
    // it when the source param has a different name from the
    // containing param (rare).
    @Serializable
    @SerialName("NodeEnumFromObjectInfo")
    data class NodeEnumFromObjectInfo(val param: String? = null) : Source

    // List files under a given ComfyUI model folder, e.g.
    // checkpoints, loras, controlnet. Pulled from
    // GET /models/{folder}.
    @Serializable
    @SerialName("ModelFolder")
    data class ModelFolder(val folder: String) : Source

    // Embedding filenames from GET /embeddings. Used for prompt
    // autocomplete on CLIPTextEncode.text (when the autocomplete
    // field on MultilineText includes "embedding").
    @Serializable
    @SerialName("EmbeddingsList")
    data object EmbeddingsList : Source
}
