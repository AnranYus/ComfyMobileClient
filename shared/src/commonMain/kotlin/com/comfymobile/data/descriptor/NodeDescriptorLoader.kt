package com.comfymobile.data.descriptor

import com.comfymobile.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Production-side glue between the Compose Multiplatform resources
 * mechanism and [NodeDescriptorRegistry].
 *
 * `:shared` ships the canonical `v1.json` under
 * `composeResources/files/node-descriptors/v1.json`; this loader
 * reads it via the generated `Res.readBytes` API at app startup.
 *
 * Tests should NOT call [load] — they parse fixture strings directly
 * via [NodeDescriptorRegistry.fromJson] to keep the resource-loading
 * step out of unit tests.
 */
object NodeDescriptorLoader {

    private const val V1_PATH = "files/node-descriptors/v1.json"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): NodeDescriptorRegistry {
        val bytes = Res.readBytes(V1_PATH)
        return NodeDescriptorRegistry.fromJson(bytes.decodeToString())
    }
}
