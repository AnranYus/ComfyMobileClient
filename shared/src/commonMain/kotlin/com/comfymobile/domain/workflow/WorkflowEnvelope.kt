package com.comfymobile.domain.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One imported workflow as the mobile client tracks it.
 *
 * Per ADR-0003: the [original] JSON is preserved verbatim — we never
 * reshape or strip fields the client doesn't recognise. Edits land on
 * a derived representation; on submit we rebuild the API-format
 * payload from the latest derived state and synthesise a UI snapshot
 * that matches it (for `extra_pnginfo.workflow`).
 */
@Serializable
data class WorkflowEnvelope(
    /** Bytes-equivalent JSON the user originally imported. */
    val original: JsonElement,
    /** Whether [original] is in UI format or API format. */
    val format: WorkflowFormat,
    /** Mobile-only metadata (label, timestamps). Not part of the wire JSON. */
    val metadata: WorkflowMetadata,
)

/**
 * Discriminator for [WorkflowEnvelope.original]. The two ComfyUI
 * workflow shapes are very different — see
 * `docs/architecture/T0.1-comfyui-integration.md` §3.
 */
@Serializable
enum class WorkflowFormat {
    /** Full editor save: top-level `nodes[]`, `links[]`, viewport, etc. */
    UI,

    /** Flat `{ node_id: { class_type, inputs } }` accepted by `POST /prompt`. */
    API,
}

/**
 * Mobile-side bookkeeping. None of these fields go on the wire.
 *
 * [createdAtEpochMs] / [lastEditedAtEpochMs] are Unix milliseconds.
 * Pure `Long` rather than a platform Date type so the value is portable
 * across KMP without expect/actual.
 */
@Serializable
data class WorkflowMetadata(
    /** User-supplied or import-derived display name. */
    val label: String,
    val createdAtEpochMs: Long,
    val lastEditedAtEpochMs: Long,
    /** Optional source hint — e.g. "imported from share-sheet" / "edited from PNG". */
    val source: String? = null,
)
