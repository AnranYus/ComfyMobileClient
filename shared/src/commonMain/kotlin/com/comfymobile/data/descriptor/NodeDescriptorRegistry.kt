package com.comfymobile.data.descriptor

import com.comfymobile.domain.node.NodeDescriptor

/**
 * In-memory lookup over the merged descriptor table. Built by
 * [NodeDescriptorRegistry.fromJson] (or, in production, by the
 * Compose-Multiplatform-resource-backed `NodeDescriptorLoader.load`)
 * once at startup; readers consume it from anywhere on any thread.
 *
 * Intentionally not a Flow / observable — descriptors load once at
 * boot. Hot-reload (Phase 4+) will rebuild a new registry instance
 * and atomically replace the singleton in DI.
 */
class NodeDescriptorRegistry private constructor(
    private val byClassType: Map<String, NodeDescriptor>,
    val version: Int,
) {

    /** Lookup descriptor by canonical ComfyUI class_type. Returns null
     * for nodes outside the v1 whitelist; UI then renders them in
     * unknown-node read-only mode (see ADR-0003). */
    fun lookup(classType: String): NodeDescriptor? = byClassType[classType]

    /** All known descriptors in original load order. Useful for
     * generating "supported nodes" lists in settings / about UI. */
    fun all(): List<NodeDescriptor> = byClassType.values.toList()

    /** Number of distinct whitelist entries. */
    val size: Int get() = byClassType.size

    /** Set of known canonical classTypes — useful when classifying
     * an imported workflow's nodes into "editable / unknown" buckets. */
    val knownClassTypes: Set<String> get() = byClassType.keys

    companion object {
        /**
         * Build a registry from a raw JSON document (the contents of
         * `node-descriptors/v1.json`). Throws
         * [InvalidDescriptorException] on any schema or semantic
         * problem; tests rely on the exception path being stable.
         */
        fun fromJson(jsonText: String): NodeDescriptorRegistry {
            val file = NodeDescriptorParser.parse(jsonText)
            val map = LinkedHashMap<String, NodeDescriptor>(file.descriptors.size)
            for (descriptor in file.descriptors) {
                map[descriptor.classType] = descriptor
            }
            return NodeDescriptorRegistry(byClassType = map, version = file.version)
        }
    }
}
