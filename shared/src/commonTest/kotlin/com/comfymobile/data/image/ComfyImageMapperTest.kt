package com.comfymobile.data.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComfyImageMapperTest {

    private val ref = ComfyOutputRef(
        filename = "ComfyUI_00001_.png",
        subfolder = "",
        type = "output",
    )

    @Test fun map_resolves_against_active_baseUrl_provider() {
        var active: String? = "http://192.168.1.10:8188"
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { active })
        val url = mapper.map(ref)
        assertNotNull(url)
        assertTrue(url.startsWith("http://192.168.1.10:8188/view"))
    }

    @Test fun map_returns_null_when_no_server_active() {
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { null })
        assertNull(mapper.map(ref))
    }

    @Test fun map_picks_up_baseUrl_change_via_provider() {
        // Server switch (e.g. user reconnected to a different LAN
        // server) must reflect on the next mapping without the
        // mapper being recreated.
        var active: String? = "http://srv-a.local:8188"
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { active })
        val urlA = mapper.map(ref)!!
        active = "http://srv-b.local:8188"
        val urlB = mapper.map(ref)!!
        assertTrue("srv-a.local" in urlA)
        assertTrue("srv-b.local" in urlB)
    }

    @Test fun map_uses_default_preview_when_no_override() {
        val defaultPreview = PreviewSpec(format = PreviewFormat.JPEG, quality = 80)
        val mapper = ComfyImageMapper(
            activeBaseUrlProvider = { "http://192.168.1.10:8188" },
            defaultPreview = defaultPreview,
        )
        val url = mapper.map(ref)
        assertNotNull(url)
        assertTrue("preview=" in url)
        assertTrue("jpeg" in url)
        assertTrue("80" in url)
    }

    @Test fun map_override_replaces_default_preview() {
        val mapper = ComfyImageMapper(
            activeBaseUrlProvider = { "http://192.168.1.10:8188" },
            defaultPreview = PreviewSpec(format = PreviewFormat.JPEG, quality = 80),
        )
        val full = mapper.map(ref, previewOverride = null)
        assertNotNull(full)
        // Override = null must SUPPRESS the default for this single call.
        assertTrue("preview=" !in full, "expected no preview, got: $full")
    }

    @Test fun map_returns_distinct_URLs_for_different_refs_on_same_server() {
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { "http://192.168.1.10:8188" })
        val urlA = mapper.map(ComfyOutputRef("a.png", "", "output"))!!
        val urlB = mapper.map(ComfyOutputRef("b.png", "", "output"))!!
        assertTrue(urlA != urlB, "different refs must produce different URLs")
    }

    @Test fun map_returns_same_URL_for_same_ref_on_same_server() {
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { "http://192.168.1.10:8188" })
        val first = mapper.map(ref)
        val second = mapper.map(ref)
        // Stable Coil cache key — running map() twice with the same
        // (server, ref) inputs must produce the same string so Coil
        // hits its memory/disk cache.
        assertEquals(first, second)
    }

    @Test fun map_returns_distinct_URLs_for_different_subfolders() {
        // ComfyUI separates outputs into subfolders for projects /
        // dates; cache must not collide on identical filenames.
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { "http://192.168.1.10:8188" })
        val a = mapper.map(ComfyOutputRef("ComfyUI_00001_.png", "session-1", "output"))!!
        val b = mapper.map(ComfyOutputRef("ComfyUI_00001_.png", "session-2", "output"))!!
        assertTrue(a != b, "different subfolders must distinguish: $a / $b")
    }

    @Test fun map_returns_distinct_URLs_for_different_types() {
        // type=output and type=temp may share filenames; cache must
        // not collide.
        val mapper = ComfyImageMapper(activeBaseUrlProvider = { "http://192.168.1.10:8188" })
        val out = mapper.map(ComfyOutputRef("ComfyUI_00001_.png", "", "output"))!!
        val temp = mapper.map(ComfyOutputRef("ComfyUI_00001_.png", "", "temp"))!!
        assertTrue(out != temp)
    }
}
