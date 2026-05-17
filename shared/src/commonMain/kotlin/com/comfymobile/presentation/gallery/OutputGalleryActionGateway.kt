package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException

/**
 * Platform action boundary for gallery-side OS features. The first
 * T2.4 slice intentionally rendered save/share/favorite as disabled
 * placeholders; this seam lets the second slice enable each action
 * only when a platform implementation is injected.
 */
interface OutputGalleryActionGateway {
    fun canSave(target: OutputGalleryActionTarget): Boolean = false

    fun canShare(target: OutputGalleryActionTarget): Boolean = false

    suspend fun save(target: OutputGalleryActionTarget): OutputGalleryActionResult =
        OutputGalleryActionResult.Unsupported

    suspend fun share(target: OutputGalleryActionTarget): OutputGalleryActionResult =
        OutputGalleryActionResult.Unsupported
}

data class OutputGalleryActionTarget(
    val ref: ComfyOutputRef,
    val imageUrl: String?,
    val contentDescription: String,
)

sealed interface OutputGalleryActionResult {
    data object Success : OutputGalleryActionResult
    data object Unsupported : OutputGalleryActionResult
    data class Failed(val message: String? = null) : OutputGalleryActionResult
}

object DisabledOutputGalleryActionGateway : OutputGalleryActionGateway

expect fun createOutputGalleryActionGateway(
    context: PlatformContext,
    httpClient: HttpClient,
): OutputGalleryActionGateway

class HttpDownloadingOutputGalleryActionGateway(
    private val httpClient: HttpClient,
    private val shareBridge: OutputGalleryShareBridge,
) : OutputGalleryActionGateway {

    override fun canShare(target: OutputGalleryActionTarget): Boolean =
        !target.imageUrl.isNullOrBlank()

    override suspend fun share(target: OutputGalleryActionTarget): OutputGalleryActionResult {
        val imageUrl = target.imageUrl ?: return OutputGalleryActionResult.Unsupported
        return try {
            val bytes: ByteArray = httpClient.get(imageUrl).body()
            shareBridge.share(
                payload = OutputGallerySharePayload(
                    bytes = bytes,
                    fileName = target.ref.filename,
                    mimeType = target.ref.mimeType,
                    contentDescription = target.contentDescription,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            OutputGalleryActionResult.Failed(t.message)
        }
    }
}

fun interface OutputGalleryShareBridge {
    suspend fun share(payload: OutputGallerySharePayload): OutputGalleryActionResult
}

data class OutputGallerySharePayload(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
    val contentDescription: String,
)

internal fun OutputGalleryItem.toActionTarget(): OutputGalleryActionTarget =
    OutputGalleryActionTarget(
        ref = ref,
        imageUrl = imageUrl,
        contentDescription = contentDescription,
    )

private val ComfyOutputRef.mimeType: String
    get() = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/png"
    }
