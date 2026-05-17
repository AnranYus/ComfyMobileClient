package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyOutputRef

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

internal fun OutputGalleryItem.toActionTarget(): OutputGalleryActionTarget =
    OutputGalleryActionTarget(
        ref = ref,
        imageUrl = imageUrl,
        contentDescription = contentDescription,
    )
