package com.comfymobile.presentation.gallery

import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

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

    private fun writeShareFile(payload: OutputGallerySharePayload): NSURL? {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/comfy-gallery-share"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val fileName = payload.fileName.sanitizedFileName()
        val path = "$directory/$fileName"
        val data = payload.bytes.toNSData()
        return if (data.writeToFile(path, atomically = true)) {
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
private fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
