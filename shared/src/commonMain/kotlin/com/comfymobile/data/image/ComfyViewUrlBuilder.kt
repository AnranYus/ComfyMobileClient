package com.comfymobile.data.image

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom

/**
 * Pure URL builder for the ComfyUI `/view` endpoint. The result is
 * a fully-qualified `http(s)://host:port/view?…` string suitable to
 * hand to any HTTP client (including Coil's image loader).
 *
 * Identical query-construction semantics to
 * [com.comfymobile.data.network.ComfyHttpClient.viewUrl], but with
 * the extra [PreviewSpec] support so the gallery layer can request
 * ComfyUI-side resampled thumbnails (cheaper than fetching the full
 * image and resizing locally).
 */
object ComfyViewUrlBuilder {

    /**
     * Build the `/view` URL.
     *
     * @param baseUrl   the active server's URL, e.g. `http://192.168.1.10:8188`.
     * @param ref       the output reference (filename + subfolder + type).
     * @param preview   if non-null, appends `preview=<format>;<quality>`
     *                  so the server resamples the asset before
     *                  returning it.
     * @param channel   optional `channel=rgb|a` selector (ComfyUI
     *                  supports it for masks). Default `null` =
     *                  whole image.
     */
    fun build(
        baseUrl: String,
        ref: ComfyOutputRef,
        preview: PreviewSpec? = null,
        channel: String? = null,
    ): String =
        URLBuilder().apply {
            takeFrom(baseUrl)
            appendPathSegments("view")
            parameters.append("filename", ref.filename)
            parameters.append("subfolder", ref.subfolder)
            parameters.append("type", ref.type)
            if (preview != null) {
                parameters.append("preview", "${preview.format.wireValue};${preview.quality}")
            }
            if (channel != null) {
                parameters.append("channel", channel)
            }
        }.buildString()
}
