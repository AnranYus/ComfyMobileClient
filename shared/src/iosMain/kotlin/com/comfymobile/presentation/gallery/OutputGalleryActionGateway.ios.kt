package com.comfymobile.presentation.gallery

import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

actual fun createOutputGalleryActionGateway(
    context: PlatformContext,
    httpClient: HttpClient,
): OutputGalleryActionGateway =
    HttpDownloadingOutputGalleryActionGateway(
        httpClient = httpClient,
        shareBridge = IosOutputGalleryShareBridge(),
    )

private class IosOutputGalleryShareBridge : OutputGalleryShareBridge {

    override suspend fun share(payload: OutputGallerySharePayload): OutputGalleryActionResult {
        val controller = rootViewController() ?: return OutputGalleryActionResult.Unsupported
        val url = writeShareFile(payload) ?: return OutputGalleryActionResult.Failed("Unable to write share file")
        val activity = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )
        controller.presentViewController(activity, animated = true, completion = null)
        return OutputGalleryActionResult.Success
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeShareFile(payload: OutputGallerySharePayload): NSURL? {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/comfy-gallery-share"
        val directoryReady = NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!directoryReady) return null
        val fileName = payload.fileName.sanitizedFileName()
        val path = "$directory/$fileName"
        return if (payload.bytes.writeToPath(path)) {
            NSURL.fileURLWithPath(path)
        } else {
            null
        }
    }

    private fun String.sanitizedFileName(): String {
        val cleaned = map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("")
        return cleaned.takeIf { it.isNotBlank() } ?: "comfy-output.png"
    }
}

private fun rootViewController(): UIViewController? =
    UIApplication.sharedApplication.keyWindow?.rootViewController

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.writeToPath(path: String): Boolean {
    val file = fopen(path, "wb") ?: return false
    return try {
        if (isEmpty()) {
            true
        } else {
            val written = usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1UL, size.toULong(), file)
            }
            written == size.toULong()
        }
    } finally {
        fclose(file)
    }
}
