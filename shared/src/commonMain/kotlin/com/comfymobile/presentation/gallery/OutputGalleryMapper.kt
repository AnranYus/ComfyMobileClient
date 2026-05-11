package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.image.PreviewSpec

object OutputGalleryMapper {
    fun items(
        outputs: List<ComfyOutputRef>,
        imageMapper: ComfyImageMapper,
        preview: PreviewSpec? = null,
    ): List<OutputGalleryItem> =
        outputs.mapIndexed { index, output ->
            OutputGalleryItem(
                ref = output,
                imageUrl = imageMapper.map(output, previewOverride = preview),
                contentDescription = "Output ${index + 1}",
            )
        }
}
