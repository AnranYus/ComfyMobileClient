package com.comfymobile.data.network

import com.comfymobile.data.network.dto.PromptRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Tests rely on [MockEngine] so no real ComfyUI is needed. Each test
 * builds a small MockEngine that asserts on the outgoing request and
 * synthesises a fixture response.
 */
class ComfyHttpClientTest {

    private val baseUrl = "http://192.168.1.10:8188"

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData):
        ComfyHttpClient {
        val mock = HttpClient(MockEngine { request -> handler(request) }) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                io.ktor.serialization.kotlinx.json.json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = false
                    }
                )
            }
        }
        return ComfyHttpClient(baseUrl = baseUrl, client = mock)
    }

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    // ---------------------------------------------------------------- system_stats

    @Test fun get_system_stats_decodes_full_payload() = runTest {
        val client = client { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/system_stats", request.url.encodedPath)
            json("""
                {
                  "system": {
                    "os": "Linux",
                    "comfyui_version": "0.3.4",
                    "python_version": "3.11.6"
                  },
                  "devices": [
                    {"name":"NVIDIA RTX 4090","type":"cuda","index":0,"vram_total":24000,"vram_free":18000}
                  ]
                }
            """.trimIndent())
        }
        val stats = client.getSystemStats()
        assertEquals("0.3.4", stats.system.comfyui_version)
        assertEquals(1, stats.devices.size)
        assertEquals("NVIDIA RTX 4090", stats.devices[0].name)
    }

    @Test fun system_stats_with_unknown_extra_field_still_parses() = runTest {
        val client = client {
            json("""{"system":{"comfyui_version":"0.3.4","brand_new_field":"something"},"devices":[]}""")
        }
        val stats = client.getSystemStats()
        assertEquals("0.3.4", stats.system.comfyui_version)
        assertTrue(stats.devices.isEmpty())
    }

    @Test fun system_stats_without_comfyui_version_throws_MissingField() = runTest {
        // Per @Lily PR #5 review: an unrelated service responding with
        // a syntactically compatible {"system":{}, "devices":[]} body
        // must NOT pass the connect probe. Catch the missing
        // signature explicitly.
        val client = client { json("""{"system":{},"devices":[]}""") }
        val ex = assertFailsWith<ComfyHttpException.MissingField> { client.getSystemStats() }
        assertEquals("/system_stats", ex.endpoint)
        assertEquals("system.comfyui_version", ex.field)
    }

    @Test fun system_stats_with_blank_comfyui_version_throws_MissingField() = runTest {
        val client = client {
            json("""{"system":{"comfyui_version":""},"devices":[]}""")
        }
        val ex = assertFailsWith<ComfyHttpException.MissingField> { client.getSystemStats() }
        assertEquals("system.comfyui_version", ex.field)
    }

    @Test fun system_stats_404_throws_HttpStatus_with_status_code() = runTest {
        val client = client {
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.NotFound,
                headers = headersOf(),
            )
        }
        val ex = assertFailsWith<ComfyHttpException.HttpStatus> { client.getSystemStats() }
        assertEquals(404, ex.statusCode)
    }

    @Test fun system_stats_malformed_body_throws_MalformedResponse() = runTest {
        val client = client {
            respond(
                content = ByteReadChannel("{not json"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val ex = assertFailsWith<ComfyHttpException.MalformedResponse> { client.getSystemStats() }
        assertEquals("/system_stats", ex.endpoint)
    }

    // ---------------------------------------------------------------- /prompt

    @Test fun submit_prompt_posts_envelope_to_prompt_endpoint() = runTest {
        var captured: HttpRequestData? = null
        val client = client { request ->
            captured = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/prompt", request.url.encodedPath)
            json("""{"prompt_id":"p-42","number":3,"node_errors":{}}""")
        }
        val req = PromptRequestDto(
            prompt = buildJsonObject { put("3", buildJsonObject { put("class_type", JsonPrimitive("KSampler")) }) },
            client_id = "client-1",
        )
        val response = client.submitPrompt(req)
        assertEquals("p-42", response.prompt_id)
        assertEquals(3, response.number)
        assertNotNull(captured)
    }

    @Test fun submit_prompt_with_node_errors_returns_them() = runTest {
        val client = client {
            json("""{"prompt_id":"p-43","number":4,"node_errors":{"3":{"errors":[{"type":"required_input_missing"}]}}}""")
        }
        val response = client.submitPrompt(
            PromptRequestDto(prompt = JsonObject(emptyMap()), client_id = "client-1")
        )
        assertEquals(1, response.node_errors.size)
        assertNotNull(response.node_errors["3"])
    }

    // ---------------------------------------------------------------- /interrupt vs /queue (delete)

    @Test fun interrupt_running_targets_interrupt_endpoint_with_optional_prompt_id() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val client = client { request ->
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                .decodeToString()
            respondOk()
        }
        client.interruptRunning(promptId = "p-1")
        assertEquals("/interrupt", capturedPath)
        assertNotNull(capturedBody)
        // body should contain prompt_id; uses kotlinx-serialization's
        // default rendering of nullable string fields.
        assertTrue(capturedBody!!.contains("\"prompt_id\":\"p-1\""))
    }

    @Test fun interrupt_running_without_prompt_id_omits_field_or_sends_null() = runTest {
        var capturedBody: String? = null
        val client = client { request ->
            capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                .decodeToString()
            respondOk()
        }
        client.interruptRunning(promptId = null)
        assertNotNull(capturedBody)
        // We don't care which form, just that the request goes out.
        // Concrete shape depends on Json{encodeDefaults}.
    }

    @Test fun delete_queued_targets_queue_endpoint_with_delete_array() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val client = client { request ->
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                .decodeToString()
            respondOk()
        }
        client.deleteQueued(promptId = "p-2")
        assertEquals("/queue", capturedPath)
        assertNotNull(capturedBody)
        assertTrue(capturedBody!!.contains("\"delete\""))
        assertTrue(capturedBody!!.contains("\"p-2\""))
    }

    @Test fun delete_queued_does_not_call_interrupt_endpoint() = runTest {
        // Regression: per @Lily review msg 1fa6de6f, the two cancel
        // paths must stay strictly separate.
        val capturedPaths = mutableListOf<String>()
        val client = client { request ->
            capturedPaths += request.url.encodedPath
            respondOk()
        }
        client.deleteQueued(promptId = "p-2")
        assertEquals(listOf("/queue"), capturedPaths)
    }

    @Test fun interrupt_running_does_not_call_queue_endpoint() = runTest {
        val capturedPaths = mutableListOf<String>()
        val client = client { request ->
            capturedPaths += request.url.encodedPath
            respondOk()
        }
        client.interruptRunning()
        assertEquals(listOf("/interrupt"), capturedPaths)
    }

    @Test fun clear_queue_targets_queue_endpoint_with_clear_true() = runTest {
        var capturedBody: String? = null
        val client = client { request ->
            assertEquals("/queue", request.url.encodedPath)
            capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                .decodeToString()
            respondOk()
        }
        client.clearQueue()
        assertNotNull(capturedBody)
        assertTrue(capturedBody!!.contains("\"clear\":true"))
    }

    // ---------------------------------------------------------------- /history

    @Test fun get_history_with_pagination_appends_query_parameters() = runTest {
        var capturedQuery: String? = null
        val client = client { request ->
            capturedQuery = request.url.encodedQuery
            json("""{}""")
        }
        client.getHistory(maxItems = 50, offset = 100)
        assertNotNull(capturedQuery)
        assertTrue(capturedQuery!!.contains("max_items=50"))
        assertTrue(capturedQuery!!.contains("offset=100"))
    }

    @Test fun get_history_entry_returns_specific_prompt_id_value() = runTest {
        val client = client {
            json("""{"p-1":{"status":{"status_str":"success","completed":true}}}""")
        }
        val entry = client.getHistoryEntry("p-1")
        assertNotNull(entry)
        assertEquals("success", entry.status?.status_str)
        assertEquals(true, entry.status?.completed)
    }

    @Test fun get_history_entry_returns_null_on_404() = runTest {
        val client = client {
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.NotFound,
                headers = headersOf(),
            )
        }
        val entry = client.getHistoryEntry("p-missing")
        assertNull(entry)
    }

    // ---------------------------------------------------------------- /view URL builder + bytes

    @Test fun view_url_builds_expected_query_string() {
        val client = ComfyHttpClient(
            baseUrl = baseUrl,
            client = HttpClient(MockEngine { respondOk() }),
        )
        val url = client.viewUrl(filename = "x.png", subfolder = "sub", type = "output")
        assertTrue(url.startsWith(baseUrl))
        assertTrue(url.contains("/view"))
        assertTrue(url.contains("filename=x.png"))
        assertTrue(url.contains("subfolder=sub"))
        assertTrue(url.contains("type=output"))
    }

    @Test fun get_view_returns_response_bytes() = runTest {
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header
        val client = client { request ->
            assertEquals("/view", request.url.encodedPath)
            assertTrue(request.url.encodedQuery.contains("filename=x.png"))
            respond(
                content = ByteReadChannel(expected),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
            )
        }
        val bytes = client.getView("x.png", "", "output")
        assertEquals(expected.toList(), bytes.toList())
    }

    // ---------------------------------------------------------------- /embeddings + /models

    @Test fun get_embeddings_returns_string_list() = runTest {
        val client = client {
            json("""["embed_a", "embed_b", "embed_c"]""")
        }
        val list = client.getEmbeddings()
        assertEquals(listOf("embed_a", "embed_b", "embed_c"), list)
    }

    @Test fun get_models_targets_models_folder_path() = runTest {
        var capturedPath: String? = null
        val client = client { request ->
            capturedPath = request.url.encodedPath
            json("""["model_a.safetensors", "model_b.safetensors"]""")
        }
        val list = client.getModels("checkpoints")
        assertEquals("/models/checkpoints", capturedPath)
        assertEquals(listOf("model_a.safetensors", "model_b.safetensors"), list)
    }

    // ---------------------------------------------------------------- /object_info

    @Test fun get_object_info_returns_json_element_tree() = runTest {
        val client = client {
            json("""{"KSampler":{"input":{"required":{}}}}""")
        }
        val tree = client.getObjectInfo()
        val ks = tree.jsonObject["KSampler"]
        assertNotNull(ks)
        assertNotNull(ks.jsonObject["input"])
    }

    // ---------------------------------------------------------------- baseUrl

    @Test fun client_handles_baseUrl_without_trailing_slash() = runTest {
        var capturedPath: String? = null
        val client = client { request ->
            capturedPath = request.url.encodedPath
            json("""{"system":{"comfyui_version":"0.3.4"},"devices":[]}""")
        }
        client.getSystemStats()
        assertEquals("/system_stats", capturedPath)
    }
}
