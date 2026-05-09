package com.comfymobile.data.network

import com.comfymobile.data.network.dto.HistoryEntryDto
import com.comfymobile.data.network.dto.HistoryMap
import com.comfymobile.data.network.dto.InterruptRequestDto
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.data.network.dto.PromptResponseDto
import com.comfymobile.data.network.dto.QueueClearRequestDto
import com.comfymobile.data.network.dto.QueueDeleteRequestDto
import com.comfymobile.data.network.dto.QueueDto
import com.comfymobile.data.network.dto.SystemStatsDto
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Thin Ktor wrapper over the ComfyUI HTTP REST surface.
 *
 * Constructor takes an existing [HttpClient] so tests can inject
 * Ktor's `MockEngine` and the production code can pass an engine-
 * specific real client (OkHttp on Android, Darwin on iOS).
 *
 * [baseUrl] is the user-entered LAN endpoint (e.g.
 * `http://192.168.1.10:8188`); we normalise it once in the
 * constructor so each request just appends a path.
 *
 * For the canonical endpoint table see
 * `docs/architecture/T0.1-comfyui-integration.md` §1.
 */
class ComfyHttpClient(
    private val baseUrl: String,
    private val client: HttpClient,
) {

    /**
     * Liveness + signature probe. Used by the connect flow to decide
     * NOT_COMFYUI vs WRONG_PORT_404 vs OK.
     *
     * @throws ComfyHttpException.HttpStatus on non-2xx
     * @throws ComfyHttpException.MalformedResponse on parse error
     */
    suspend fun getSystemStats(): SystemStatsDto =
        get(path = "/system_stats", endpointTag = "/system_stats")

    /** Full node-class catalog. The result is huge; cache aggressively. */
    suspend fun getObjectInfo(): JsonElement =
        get(path = "/object_info", endpointTag = "/object_info")

    /** Single node-class detail; for incremental fetch. */
    suspend fun getObjectInfo(classType: String): JsonElement =
        get(path = "/object_info/$classType", endpointTag = "/object_info/{classType}")

    /** List of available embedding filenames. */
    suspend fun getEmbeddings(): List<String> =
        get(path = "/embeddings", endpointTag = "/embeddings")

    /** Files under a model folder (`checkpoints`, `loras`, etc.). */
    suspend fun getModels(folder: String): List<String> =
        get(path = "/models/$folder", endpointTag = "/models/{folder}")

    /** Current queue counters. */
    suspend fun getQueue(): QueueDto =
        get(path = "/queue", endpointTag = "/queue")

    /** Paginated execution history. */
    suspend fun getHistory(maxItems: Int? = null, offset: Int? = null): HistoryMap {
        val response = client.get(buildUrl("/history")) {
            maxItems?.let { parameter("max_items", it) }
            offset?.let { parameter("offset", it) }
        }
        return decode(response, "/history")
    }

    /** History entry for a specific prompt — authoritative state. */
    suspend fun getHistoryEntry(promptId: String): HistoryEntryDto? {
        val map = getHistoryEntryMap(promptId) ?: return null
        // /history/{id} returns a single-key map keyed by the prompt id.
        return map[promptId]
    }

    private suspend fun getHistoryEntryMap(promptId: String): Map<String, HistoryEntryDto>? {
        val response = client.get(buildUrl("/history/$promptId"))
        return when (response.status) {
            HttpStatusCode.OK -> decode<Map<String, HistoryEntryDto>>(response, "/history/{id}")
            HttpStatusCode.NotFound -> null
            else -> throw ComfyHttpException.HttpStatus(
                statusCode = response.status.value,
                responseBody = response.bodyAsText(),
            )
        }
    }

    /** Fetch image / preview bytes for an output. */
    suspend fun getView(filename: String, subfolder: String, type: String): ByteArray {
        val response = client.get(buildUrl("/view")) {
            parameter("filename", filename)
            parameter("subfolder", subfolder)
            parameter("type", type)
        }
        if (!response.status.isSuccess()) {
            throw ComfyHttpException.HttpStatus(
                statusCode = response.status.value,
                responseBody = null,
            )
        }
        return response.bodyAsBytes()
    }

    /** Build the public URL for a `/view` asset without fetching it
     *  — handy for image-loader integrations (Coil) that need a URL. */
    fun viewUrl(filename: String, subfolder: String, type: String): String =
        URLBuilder().apply {
            takeFrom(baseUrl)
            appendPathSegments("view")
            parameters.append("filename", filename)
            parameters.append("subfolder", subfolder)
            parameters.append("type", type)
        }.buildString()

    // ----------------------------------------------------------------- url

    // ----------------------------------------------------------------- POST

    /**
     * Submit a workflow. Returns the accepted prompt id + queue
     * position, and any per-node validation errors so the caller can
     * surface "your workflow has issues" without a separate roundtrip.
     */
    suspend fun submitPrompt(request: PromptRequestDto): PromptResponseDto =
        post(path = "/prompt", endpointTag = "/prompt", body = request)

    /**
     * Cancel the *currently running* prompt. Optional [promptId]
     * targets a specific run when the server is processing it; absent
     * defaults to "whatever is running now".
     *
     * Strictly separate from [deleteQueued] (per @Lily seam in
     * #ComfyMobile:af119226 msg `1fa6de6f`). Do NOT route both through
     * a single boolean-flagged method — the network path is different
     * (`/interrupt` vs `/queue {delete}`).
     */
    suspend fun interruptRunning(promptId: String? = null) {
        val body = InterruptRequestDto(prompt_id = promptId)
        val response = client.post(buildUrl("/interrupt")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(response, "/interrupt")
    }

    /**
     * Remove a prompt that is queued but not yet running.
     *
     * Strictly separate from [interruptRunning].
     */
    suspend fun deleteQueued(promptId: String) {
        val body = QueueDeleteRequestDto(delete = listOf(promptId))
        val response = client.post(buildUrl("/queue")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        ensureSuccess(response, "/queue")
    }

    /** Empty the entire queue. Different action from [deleteQueued]. */
    suspend fun clearQueue() {
        val response = client.post(buildUrl("/queue")) {
            contentType(ContentType.Application.Json)
            setBody(QueueClearRequestDto(clear = true))
        }
        ensureSuccess(response, "/queue")
    }

    // ----------------------------------------------------------------- helpers

    private suspend inline fun <reified T> get(path: String, endpointTag: String): T {
        val response = client.get(buildUrl(path))
        return decode(response, endpointTag)
    }

    private suspend inline fun <reified T> post(
        path: String,
        endpointTag: String,
        body: Any,
    ): T {
        val response = client.post(buildUrl(path)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return decode(response, endpointTag)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse, endpointTag: String): T {
        if (!response.status.isSuccess()) {
            throw ComfyHttpException.HttpStatus(
                statusCode = response.status.value,
                responseBody = response.bodyAsText(),
            )
        }
        return try {
            response.body()
        } catch (t: Throwable) {
            throw ComfyHttpException.MalformedResponse(
                endpoint = endpointTag,
                reason = t.message ?: t::class.simpleName ?: "deserialization failed",
                cause = t,
            )
        }
    }

    private suspend fun ensureSuccess(response: HttpResponse, endpoint: String) {
        if (!response.status.isSuccess()) {
            throw ComfyHttpException.HttpStatus(
                statusCode = response.status.value,
                responseBody = response.bodyAsText(),
            )
        }
    }

    private fun buildUrl(path: String): String =
        URLBuilder().apply {
            takeFrom(baseUrl)
            // Use Ktor's built-in extension; works on Linux/JVM/Native.
            appendPathSegments(*path.removePrefix("/").split("/").toTypedArray())
        }.buildString()

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    companion object {

        /**
         * Create a default-configured [HttpClient] suitable for
         * ComfyUI: kotlinx-serialization JSON content-negotiation,
         * WebSocket plugin (used by [ComfyWebSocketClient] in
         * T1.1 part 2b), no auth, no caching.
         *
         * Tests pass their own [HttpClient] built around `MockEngine`.
         *
         * @param engine optional explicit engine; production code
         *               omits it and lets Ktor auto-resolve from the
         *               `ktor-client-okhttp` (Android) /
         *               `ktor-client-darwin` (iOS) dependency on the
         *               classpath.
         */
        fun defaultClient(engine: HttpClientEngine? = null): HttpClient {
            val config: HttpClientConfig<*>.() -> Unit = {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = false
                        }
                    )
                }
                install(WebSockets)
            }
            return if (engine != null) HttpClient(engine, config) else HttpClient(config)
        }
    }
}
