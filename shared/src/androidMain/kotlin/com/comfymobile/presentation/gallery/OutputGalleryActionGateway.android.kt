package com.comfymobile.presentation.gallery

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.comfymobile.data.platform.PlatformContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import java.io.File

actual fun createOutputGalleryActionGateway(
    context: PlatformContext,
    httpClient: HttpClient,
): OutputGalleryActionGateway =
    HttpDownloadingOutputGalleryActionGateway(
        httpClient = httpClient,
        shareBridge = AndroidOutputGalleryShareBridge(context.androidContext),
        // Save uses MediaStore scoped storage (Android 10+, API 29+)
        // so no runtime permission is required. On older devices we
        // leave the bridge unset so `canSave()` reports false and the
        // UI button stays disabled — full permission UX is a separate
        // Phase 3+ follow-up per @Ores msg `51d2617c`.
        saveBridge = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AndroidOutputGallerySaveBridge(context.androidContext)
        } else {
            null
        },
    )

/**
 * Save-to-photo-library Android bridge.
 *
 * Per @Ores T2.7 spec follow-up (msg `51d2617c`): we deliberately
 * avoid the legacy WRITE_EXTERNAL_STORAGE permission path. Save is
 * only enabled on Android 10+ (API 29+) where MediaStore scoped
 * storage (`RELATIVE_PATH` + `IS_PENDING`) writes to `Pictures/Comfy`
 * without any runtime permission request. Older devices fall through
 * to [OutputGalleryActionResult.Unsupported] — the UI's Save button
 * stays disabled there. Full permission UX (deny / permanent-deny /
 * settings deep-link) is a separate spec'd Phase 3+ follow-up.
 */
private class AndroidOutputGallerySaveBridge(
    private val context: Context,
) : OutputGallerySaveBridge {

    override suspend fun save(payload: OutputGallerySavePayload): OutputGalleryActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Android-10 needs WRITE_EXTERNAL_STORAGE; out of scope
            // for this PR. Caller's `canSave()` already filters this
            // via `Build.VERSION.SDK_INT >= Q` in the wrapper below.
            return OutputGalleryActionResult.Unsupported
        }
        val fileName = payload.fileName.sanitizedFileName()
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, payload.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + "Comfy",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, pending)
            ?: return OutputGalleryActionResult.Failed("MediaStore insert returned null")
        // Single rollback path used by every error branch below. Per
        // @Lily PR #38 review (`4472135830`): any failure AFTER insert
        // MUST delete the pending row so we don't leak a half-written
        // (or 0-byte) ghost entry into the gallery.
        return try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.tryDelete(uri)
                return OutputGalleryActionResult.Failed("Cannot open MediaStore output stream")
            }
            stream.use { out ->
                out.write(payload.bytes)
                out.flush()
            }
            // Phase 2 of two-phase MediaStore insert: clear IS_PENDING
            // so the image becomes visible to the photo gallery and
            // other apps. `update` returns the number of rows actually
            // modified — per @Lily PR #38 review (`4472135830`) we
            // MUST treat a zero-row update as failure (the row may
            // have been deleted by the system between insert and
            // finalize) and roll back accordingly. Only an actual
            // affected-row count of ≥1 counts as Success.
            val finalize = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            val affected = resolver.update(uri, finalize, null, null)
            if (affected <= 0) {
                resolver.tryDelete(uri)
                OutputGalleryActionResult.Failed("MediaStore finalize update affected 0 rows")
            } else {
                OutputGalleryActionResult.Success
            }
        } catch (ce: CancellationException) {
            resolver.tryDelete(uri)
            throw ce
        } catch (t: Throwable) {
            resolver.tryDelete(uri)
            OutputGalleryActionResult.Failed(t.message)
        }
    }

    /**
     * Best-effort pending-row rollback. Failures inside this helper
     * are intentionally swallowed — surfacing the rollback error
     * would mask the original failure, and the row will eventually be
     * collected by MediaStore even if delete fails here.
     */
    private fun android.content.ContentResolver.tryDelete(uri: Uri) {
        try {
            delete(uri, null, null)
        } catch (_: Throwable) { /* swallow rollback error */ }
    }

    private fun String.sanitizedFileName(): String {
        val cleaned = map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("")
        return cleaned.takeIf { it.isNotBlank() } ?: "comfy-output.png"
    }
}

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
