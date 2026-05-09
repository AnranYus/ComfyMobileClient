package com.comfymobile.data.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComfyViewUrlBuilderTest {

    private val baseUrl = "http://192.168.1.10:8188"

    @Test fun build_appends_view_path_with_three_query_params() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("ComfyUI_00001_.png", "", "output"),
        )
        assertTrue(url.startsWith(baseUrl), "expected baseUrl prefix, got: $url")
        assertTrue("/view" in url, "missing /view path: $url")
        assertTrue("filename=ComfyUI_00001_.png" in url, "missing filename: $url")
        // Empty subfolder still emitted so the server's parameter
        // parser sees three params.
        assertTrue("subfolder=" in url, "missing subfolder= : $url")
        assertTrue("type=output" in url, "missing type=output: $url")
    }

    @Test fun build_url_encodes_filename_with_special_characters() {
        // ComfyUI's webui can produce filenames with spaces, brackets,
        // and Unicode (the user might have set filename_prefix to
        // "我的图片"). Make sure we don't end up with a malformed URL.
        val ref = ComfyOutputRef(
            filename = "test image (1).png",
            subfolder = "工作流 sub",
            type = "output",
        )
        val url = ComfyViewUrlBuilder.build(baseUrl, ref)
        // Spaces must be % encoded (or +) — never bare. Brackets
        // similarly. Critical bit: no raw ' ', '(', ')' in the URL
        // outside the host part.
        val tail = url.substringAfter("/view")
        assertTrue(' ' !in tail, "raw space in query: $tail")
        assertTrue('(' !in tail, "raw paren in query: $tail")
        // Unicode in subfolder must be percent-encoded.
        assertTrue("工作流" !in tail, "raw Unicode survived in query: $tail")
    }

    @Test fun build_with_preview_appends_preview_query_param() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("x.png", "", "output"),
            preview = PreviewSpec(format = PreviewFormat.JPEG, quality = 90),
        )
        // ComfyUI accepts `preview=jpeg;90` (URL-encoded ';' as %3B).
        // Verify the form/quality bytes appear regardless of the
        // separator encoding.
        assertTrue("preview=" in url)
        assertTrue("jpeg" in url)
        assertTrue("90" in url)
    }

    @Test fun build_with_preview_webp_uses_webp_format() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("x.png", "", "output"),
            preview = PreviewSpec(format = PreviewFormat.WEBP, quality = 75),
        )
        assertTrue("webp" in url)
        assertTrue("75" in url)
    }

    @Test fun build_without_preview_does_not_include_preview_param() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("x.png", "", "output"),
            preview = null,
        )
        assertTrue("preview=" !in url)
    }

    @Test fun build_with_channel_appends_channel_param() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("mask.png", "", "output"),
            channel = "a",
        )
        assertTrue("channel=a" in url)
    }

    @Test fun preview_quality_1_is_accepted() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("x.png", "", "output"),
            preview = PreviewSpec(format = PreviewFormat.JPEG, quality = 1),
        )
        assertTrue("1" in url)
    }

    @Test fun preview_quality_100_is_accepted() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ComfyOutputRef("x.png", "", "output"),
            preview = PreviewSpec(format = PreviewFormat.JPEG, quality = 100),
        )
        assertTrue("100" in url)
    }

    @Test fun preview_quality_zero_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            PreviewSpec(format = PreviewFormat.JPEG, quality = 0)
        }
    }

    @Test fun preview_quality_negative_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            PreviewSpec(format = PreviewFormat.JPEG, quality = -1)
        }
    }

    @Test fun preview_quality_above_100_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            PreviewSpec(format = PreviewFormat.JPEG, quality = 101)
        }
    }

    @Test fun build_with_https_baseUrl_preserves_scheme() {
        val url = ComfyViewUrlBuilder.build(
            baseUrl = "https://example.com:8443",
            ref = ComfyOutputRef("x.png", "", "output"),
        )
        assertTrue(url.startsWith("https://"), "expected https scheme: $url")
        assertTrue("example.com" in url)
    }

    @Test fun build_with_baseUrl_trailing_slash_is_normalised() {
        val urlA = ComfyViewUrlBuilder.build(
            baseUrl = "http://192.168.1.10:8188",
            ref = ComfyOutputRef("x.png", "", "output"),
        )
        val urlB = ComfyViewUrlBuilder.build(
            baseUrl = "http://192.168.1.10:8188/",
            ref = ComfyOutputRef("x.png", "", "output"),
        )
        // Different trailing slashes must produce equivalent URLs
        // (both should hit /view, not //view).
        assertTrue("/view" in urlA && "//view" !in urlA, "double slash in: $urlA")
        assertTrue("/view" in urlB && "//view" !in urlB, "double slash in: $urlB")
    }
}
