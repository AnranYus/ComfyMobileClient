package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyOutputRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutputGalleryActionGatewayTest {

    @Test fun share_downloads_image_payload_and_delegates_to_platform_bridge() = runTest {
        val bridge = RecordingShareBridge()
        val gateway = HttpDownloadingOutputGalleryActionGateway(
            httpClient = HttpClient(MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("http://server/view", request.url.toString())
                respond(
                    content = ByteReadChannel(byteArrayOf(1, 2, 3)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                )
            }),
            shareBridge = bridge,
        )
        val target = target(imageUrl = "http://server/view")

        assertTrue(gateway.canShare(target))
        val result = gateway.share(target)

        assertEquals(OutputGalleryActionResult.Success, result)
        val payload = bridge.payloads.single()
        assertContentEquals(byteArrayOf(1, 2, 3), payload.bytes)
        assertEquals("one.png", payload.fileName)
        assertEquals("image/png", payload.mimeType)
    }

    @Test fun share_is_unavailable_without_resolved_image_url() = runTest {
        val gateway = HttpDownloadingOutputGalleryActionGateway(
            httpClient = HttpClient(MockEngine { error("unexpected network call") }),
            shareBridge = RecordingShareBridge(),
        )
        val target = target(imageUrl = null)

        assertFalse(gateway.canShare(target))
        assertIs<OutputGalleryActionResult.Unsupported>(gateway.share(target))
    }

    // ---------------------------------------------------------------- save delegation (T2.4 follow-up Android actuals)

    @Test fun save_without_save_bridge_reports_unsupported_and_does_not_call_network() = runTest {
        // Default behaviour preserved when no platform save bridge is
        // injected (mirrors the pre-T2.4-follow-up state on iOS or on
        // Android pre-10 where MediaStore scoped storage isn't
        // available). Crucially the gateway MUST NOT call the network
        // before checking the bridge — that would waste bandwidth on a
        // call whose result it can't deliver.
        val gateway = HttpDownloadingOutputGalleryActionGateway(
            httpClient = HttpClient(MockEngine { error("unexpected network call") }),
            shareBridge = RecordingShareBridge(),
            saveBridge = null,
        )
        val target = target(imageUrl = "http://server/view")

        assertFalse(gateway.canSave(target))
        assertIs<OutputGalleryActionResult.Unsupported>(gateway.save(target))
    }

    @Test fun save_downloads_image_payload_and_delegates_to_platform_bridge() = runTest {
        val saveBridge = RecordingSaveBridge()
        val gateway = HttpDownloadingOutputGalleryActionGateway(
            httpClient = HttpClient(MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("http://server/view", request.url.toString())
                respond(
                    content = ByteReadChannel(byteArrayOf(4, 5, 6)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                )
            }),
            shareBridge = RecordingShareBridge(),
            saveBridge = saveBridge,
        )
        val target = target(imageUrl = "http://server/view")

        assertTrue(gateway.canSave(target))
        val result = gateway.save(target)

        assertEquals(OutputGalleryActionResult.Success, result)
        val payload = saveBridge.payloads.single()
        assertContentEquals(byteArrayOf(4, 5, 6), payload.bytes)
        assertEquals("one.png", payload.fileName)
        assertEquals("image/png", payload.mimeType)
        assertEquals("Output 1", payload.contentDescription)
    }

    @Test fun save_is_unavailable_without_resolved_image_url_even_with_bridge() = runTest {
        // canSave gates on imageUrl too — a bridge with no URL has
        // nothing to save, and the UI button must stay disabled.
        val gateway = HttpDownloadingOutputGalleryActionGateway(
            httpClient = HttpClient(MockEngine { error("unexpected network call") }),
            shareBridge = RecordingShareBridge(),
            saveBridge = RecordingSaveBridge(),
        )
        val target = target(imageUrl = null)

        assertFalse(gateway.canSave(target))
        assertIs<OutputGalleryActionResult.Unsupported>(gateway.save(target))
    }

    @Test fun save_surfaces_bridge_failure_as_Failed_result() = runTest {
        val gateway = HttpDownloadingOutputGalleryActionGateway(
            httpClient = HttpClient(MockEngine {
                respond(
                    content = ByteReadChannel(byteArrayOf(1)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                )
            }),
            shareBridge = RecordingShareBridge(),
            saveBridge = OutputGallerySaveBridge {
                OutputGalleryActionResult.Failed("MediaStore insert failed")
            },
        )
        val result = gateway.save(target(imageUrl = "http://server/view"))
        val failed = result as OutputGalleryActionResult.Failed
        assertEquals("MediaStore insert failed", failed.message)
    }

    private fun target(imageUrl: String?): OutputGalleryActionTarget =
        OutputGalleryActionTarget(
            ref = ComfyOutputRef("one.png", "", "output"),
            imageUrl = imageUrl,
            contentDescription = "Output 1",
        )

    private class RecordingShareBridge : OutputGalleryShareBridge {
        val payloads = mutableListOf<OutputGallerySharePayload>()

        override suspend fun share(payload: OutputGallerySharePayload): OutputGalleryActionResult {
            payloads += payload
            return OutputGalleryActionResult.Success
        }
    }

    private class RecordingSaveBridge : OutputGallerySaveBridge {
        val payloads = mutableListOf<OutputGallerySavePayload>()

        override suspend fun save(payload: OutputGallerySavePayload): OutputGalleryActionResult {
            payloads += payload
            return OutputGalleryActionResult.Success
        }
    }
}
