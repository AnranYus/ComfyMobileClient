package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.image.PreviewFormat
import com.comfymobile.data.image.PreviewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputGalleryMapperTest {

    @Test fun maps_output_refs_to_active_server_view_urls() {
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { "http://192.168.1.10:8188" })
        val rows = OutputGalleryMapper.items(
            outputs = listOf(ComfyOutputRef("ComfyUI_00001_.png", "batch", "output")),
            imageMapper = mapper,
            preview = PreviewSpec(PreviewFormat.JPEG, 90),
        )

        assertEquals("Output 1", rows.single().contentDescription)
        assertEquals(
            "http://192.168.1.10:8188/view?filename=ComfyUI_00001_.png&subfolder=batch&type=output&preview=jpeg%3B90",
            rows.single().imageUrl,
        )
    }

    @Test fun leaves_url_null_when_no_active_server() {
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { null })
        val rows = OutputGalleryMapper.items(
            outputs = listOf(ComfyOutputRef("a.png", "", "output")),
            imageMapper = mapper,
        )

        assertNull(rows.single().imageUrl)
    }
}
