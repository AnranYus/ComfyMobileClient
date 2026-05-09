package com.comfymobile.domain.node

import kotlinx.serialization.Serializable

/**
 * One editable parameter on a whitelisted node. The UI uses [control]
 * to decide which Composable to render; [name] is the JSON inputs key
 * we write back into the workflow on submit.
 */
@Serializable
data class ParamDescriptor(
    val name: String,
    val displayName: LocalizedString,
    val control: ControlType,
    val helpText: LocalizedString? = null,
)

/**
 * One whitelisted ComfyUI node class with its mobile-editable
 * parameter set. `class_type` strings (the [classType] field) are the
 * canonical ComfyUI node identifiers — see `docs/CONTEXT.md` v4.
 *
 * If a workflow contains a node whose class_type is NOT in the
 * registry, the UI renders it read-only and preserves the JSON
 * verbatim (see ADR-0003).
 */
@Serializable
data class NodeDescriptor(
    val classType: String,
    val displayName: LocalizedString,
    val helpText: LocalizedString? = null,
    val category: String,
    val editableParams: List<ParamDescriptor> = emptyList(),
)

/**
 * Top-level shape of `node-descriptors/v1.json`. The schema version
 * is bumped (v1 → v2 → …) when a breaking change is needed; the
 * registry loader fails fast on an unsupported version.
 */
@Serializable
data class NodeDescriptorsFile(
    val version: Int,
    val descriptors: List<NodeDescriptor>,
)
