package com.comfymobile.presentation.history

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.domain.job.JobOutputRef

class ComfyHistoryThumbnailMapper(
    private val imageMapper: ComfyImageMapper,
) : HistoryThumbnailMapper {
    override fun map(output: JobOutputRef): String? =
        imageMapper.map(
            ref = ComfyOutputRef(
                filename = output.filename,
                subfolder = output.subfolder,
                type = output.type,
            ),
        )
}
