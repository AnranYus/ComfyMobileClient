package com.comfymobile.presentation.gallery

import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.ComfyOutputRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputGalleryViewModelTest {

    @Test fun show_single_output_opens_viewer_immediately() {
        val vm = viewModel()

        vm.show(outputs = listOf(ComfyOutputRef("one.png", "", "output")), title = "Job")

        assertEquals("Job", vm.state.value.title)
        assertEquals(1, vm.state.value.items.size)
        assertEquals(0, vm.state.value.selectedIndex)
        assertEquals("http://server/view?filename=one.png&subfolder=&type=output&preview=jpeg%3B90", vm.state.value.items.single().imageUrl)
    }

    @Test fun show_multiple_outputs_starts_in_grid() {
        val vm = viewModel()

        vm.show(
            outputs = listOf(
                ComfyOutputRef("one.png", "", "output"),
                ComfyOutputRef("two.png", "", "output"),
            ),
        )

        assertEquals(2, vm.state.value.items.size)
        assertNull(vm.state.value.selectedIndex)

        vm.openItem(1)
        assertEquals(1, vm.state.value.selectedIndex)
    }

    @Test fun batch_selection_toggles_by_index() {
        val vm = viewModel()
        vm.show(
            outputs = listOf(
                ComfyOutputRef("one.png", "", "output"),
                ComfyOutputRef("two.png", "", "output"),
            ),
        )

        vm.toggleBatchSelection(0)
        assertTrue(0 in vm.state.value.selectedForBatch)
        vm.toggleBatchSelection(0)
        assertTrue(vm.state.value.selectedForBatch.isEmpty())
    }

    private fun viewModel(): OutputGalleryViewModel =
        OutputGalleryViewModel(
            imageMapper = ComfyImageMapper(activeBaseUrlProvider = { "http://server" }),
        )
}
