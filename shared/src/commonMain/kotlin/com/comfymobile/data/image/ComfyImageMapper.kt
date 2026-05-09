package com.comfymobile.data.image

/**
 * Maps a [ComfyOutputRef] (plus an active server's base URL and an
 * optional [PreviewSpec]) to the absolute URL that Coil's image
 * loader will fetch.
 *
 * Designed to slot into Coil 3's `Mapper<ComfyOutputRef, String>`
 * extension point in T1.4. We don't take a direct Coil dependency
 * here so the mapping logic stays unit-testable in commonTest
 * without pulling Coil into the test classpath.
 *
 * The [activeBaseUrlProvider] is a function rather than a captured
 * `String` so the gallery can keep displaying images after the user
 * switches between LAN servers without reconstructing the mapper.
 *
 * ## Cache-key boundary
 *
 * Coil's memory + disk cache is keyed by the resolved URL string.
 * This means:
 *  - a `/view` URL with `preview=jpeg;90` cache-misses against the
 *    same URL without `preview=` — that's correct (different
 *    payloads).
 *  - the same `(filename, subfolder, type)` from two different
 *    prompts maps to the same URL on the same server, so Coil
 *    correctly dedupes.
 *
 * Product attribution (which prompt produced which outputs) lives
 * in the local job index, not in the URL.
 */
class ComfyImageMapper(
    private val activeBaseUrlProvider: () -> String?,
    private val defaultPreview: PreviewSpec? = null,
) {

    /**
     * Resolve [ref] against the currently-active server. Returns
     * null when no server is connected (caller renders an empty
     * placeholder rather than firing a request).
     *
     * @param previewOverride if non-null, replaces [defaultPreview]
     *        for this single resolution (useful when the gallery
     *        wants thumbnails but the lightbox wants full-res).
     */
    fun map(ref: ComfyOutputRef, previewOverride: PreviewSpec? = defaultPreview): String? {
        val baseUrl = activeBaseUrlProvider() ?: return null
        return ComfyViewUrlBuilder.build(
            baseUrl = baseUrl,
            ref = ref,
            preview = previewOverride,
        )
    }
}
