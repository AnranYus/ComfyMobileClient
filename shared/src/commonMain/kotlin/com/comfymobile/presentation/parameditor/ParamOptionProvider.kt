package com.comfymobile.presentation.parameditor

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.domain.node.Source
import com.comfymobile.domain.server.ServerInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException

fun interface ParamOptionProvider {
    suspend fun load(request: ParamOptionRequest): Result<List<ParamOption>>
}

object EmptyParamOptionProvider : ParamOptionProvider {
    override suspend fun load(request: ParamOptionRequest): Result<List<ParamOption>> =
        Result.success(emptyList())
}

/**
 * Live ComfyUI-backed option source for Dropdown / ModelPicker /
 * prompt autocomplete pickers. Kept behind [ParamOptionProvider] so
 * tests and previews can run without a server or Ktor MockEngine.
 */
class ComfyParamOptionProvider(
    private val httpClient: ComfyHttpClient,
) : ParamOptionProvider {

    override suspend fun load(request: ParamOptionRequest): Result<List<ParamOption>> =
        try {
            Result.success(
                when (val source = request.source) {
                is Source.NodeEnumFromObjectInfo -> loadNodeEnum(request, source)
                is Source.ModelFolder -> httpClient.getModels(source.folder).map { ParamOption(it) }
                Source.EmbeddingsList -> httpClient.getEmbeddings().map { ParamOption(it) }
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private suspend fun loadNodeEnum(
        request: ParamOptionRequest,
        source: Source.NodeEnumFromObjectInfo,
    ): List<ParamOption> {
        val objectInfo = httpClient.getObjectInfo(request.classType)
        val paramName = source.param ?: request.paramName
        val classInfo = objectInfo.classInfoFor(request.classType) ?: return emptyList()
        val required = (((classInfo["input"] as? JsonObject)
            ?.get("required")) as? JsonObject) ?: return emptyList()
        val spec = required[paramName] as? JsonArray ?: return emptyList()
        val enumValues = spec.getOrNull(0) as? JsonArray ?: return emptyList()
        return enumValues.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.let { ParamOption(it) }
        }
    }

    private fun JsonElement.classInfoFor(classType: String): JsonObject? {
        val obj = this as? JsonObject ?: return null
        return (obj[classType] as? JsonObject) ?: obj
    }
}

/**
 * Production provider that resolves the active ComfyUI server at the
 * moment options are requested. This keeps the drawer from pinning a
 * stale base URL when the user switches servers between selections.
 */
class ActiveServerParamOptionProvider(
    private val activeServer: ActiveServerHolder,
    private val httpClientFactory: (ServerInfo) -> ComfyHttpClient,
) : ParamOptionProvider {
    override suspend fun load(request: ParamOptionRequest): Result<List<ParamOption>> {
        val server = activeServer.current.value
            ?: return Result.failure(IllegalStateException("No active server selected"))
        return ComfyParamOptionProvider(httpClientFactory(server)).load(request)
    }
}
