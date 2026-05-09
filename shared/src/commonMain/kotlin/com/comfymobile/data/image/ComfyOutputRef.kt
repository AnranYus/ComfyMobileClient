package com.comfymobile.data.image

/**
 * Domain reference to a single image asset on a ComfyUI server. Maps
 * directly to the three query parameters `/view` accepts:
 *
 *   GET /view?filename=...&subfolder=...&type=output
 *
 * Used as the input key for [ComfyImageMapper] and the cache key
 * within Coil. The asset is fetched server-relatively, so the
 * absolute URL is only resolved at request time using the active
 * server's base URL — there's no point baking the host into this
 * value object.
 *
 * **Cache-key scope (per @Lily PR #9 part 2 review msg `60751352`):**
 *
 * The Coil URL layer caches by the ComfyUI file triple
 * `(filename, subfolder, type)`. ComfyUI itself disambiguates output
 * filenames per execution, so two different prompts producing two
 * different images will never share a triple — but in case of a
 * server-side rename or replay, two different `prompt_id`s could in
 * theory map to the same triple, and the URL cache would (correctly)
 * dedupe them.
 *
 * **Product history attribution does NOT go through URL reverse
 * mapping.** The local job index ([com.comfymobile.domain.job.Job])
 * stores its own per-output metadata (which prompt produced which
 * outputs), and the gallery layer in T1.4 reads that metadata
 * directly. Never try to infer "which job did this URL come from"
 * from the URL itself — that mapping is one-way.
 */
data class ComfyOutputRef(
    val filename: String,
    val subfolder: String,
    val type: String,
) {
    companion object {
        /** ComfyUI's convention for the three folder types. */
        const val TYPE_OUTPUT: String = "output"
        const val TYPE_INPUT: String = "input"
        const val TYPE_TEMP: String = "temp"
    }
}

/**
 * Optional preview parameters appended to the `/view` URL when the
 * caller wants ComfyUI to do server-side resampling. Both ComfyUI
 * forms (jpeg / webp + integer quality 1..100) are supported.
 *
 *   /view?...&preview=jpeg;90
 *
 * For full-resolution images, leave this null.
 */
data class PreviewSpec(
    val format: PreviewFormat,
    val quality: Int,
) {
    init {
        require(quality in 1..100) { "quality must be 1..100, got $quality" }
    }
}

enum class PreviewFormat(val wireValue: String) {
    JPEG("jpeg"),
    WEBP("webp"),
}
