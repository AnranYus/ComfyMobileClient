package com.comfymobile.data.descriptor

/**
 * Thrown when a descriptor file fails schema or semantic validation.
 *
 * [path] is a slash-style descriptor-path that points at the offending
 * piece of the file, e.g.
 * `descriptors[KSampler].editableParams[cfg].control.min`. Tests rely
 * on this path being stable across kotlinx-serialization versions —
 * the loader catches any low-level parse exception and re-wraps it
 * with the precise path it was inspecting.
 */
class InvalidDescriptorException(
    val path: String,
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException("Invalid descriptor at $path: $reason", cause)
