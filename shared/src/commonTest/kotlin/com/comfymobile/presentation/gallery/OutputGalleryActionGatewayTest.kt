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
}
