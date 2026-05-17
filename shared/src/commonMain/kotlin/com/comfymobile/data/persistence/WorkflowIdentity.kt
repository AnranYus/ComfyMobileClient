package com.comfymobile.data.persistence

import com.comfymobile.domain.workflow.WorkflowEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object WorkflowIdentity {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun workflowIdFor(envelope: WorkflowEnvelope): String {
        val canonical = envelope.format.name + ":" + json.encodeToString(
            JsonElement.serializer(),
            canonicalize(envelope.original),
        )
        return "wf_" + fnv1a64(canonical.encodeToByteArray()).toULong().toString(radix = 36)
    }

    private fun canonicalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .sortedBy { it.key }
                    .associate { (key, value) -> key to canonicalize(value) }
            )
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = FNV_64_OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= FNV_64_PRIME
        }
        return hash
    }

    private const val FNV_64_OFFSET_BASIS: Long = -3750763034362895579L
    private const val FNV_64_PRIME: Long = 1099511628211L
}
