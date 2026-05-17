package com.comfymobile.presentation.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient
import java.io.File

actual fun createOutputGalleryActionGateway(
    context: PlatformContext,
    httpClient: HttpClient,
): OutputGalleryActionGateway =
    HttpDownloadingOutputGalleryActionGateway(
        httpClient = httpClient,
        shareBridge = AndroidOutputGalleryShareBridge(context.androidContext),
    )

private class AndroidOutputGalleryShareBridge(
    private val context: Context,
) : OutputGalleryShareBridge {

    override suspend fun share(payload: OutputGallerySharePayload): OutputGalleryActionResult {
        val file = writeShareFile(payload)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, payload.contentDescription, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return OutputGalleryActionResult.Success
    }

    private fun writeShareFile(payload: OutputGallerySharePayload): File {
        val dir = File(context.cacheDir, "comfy-gallery-share").apply {
            mkdirs()
        }
        val file = File(dir, payload.fileName.sanitizedFileName())
        file.writeBytes(payload.bytes)
        return file
    }

    private fun String.sanitizedFileName(): String {
        val cleaned = map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("")
        return cleaned.takeIf { it.isNotBlank() } ?: "comfy-output.png"
    }
}
