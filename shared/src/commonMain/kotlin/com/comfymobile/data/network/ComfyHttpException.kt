package com.comfymobile.data.network

/**
 * Thrown by [ComfyHttpClient] when an HTTP call fails in a structured
 * way — non-2xx response, missing required fields in a successful
 * response, or unexpected payload shape.
 *
 * Network-layer failures (timeouts, refused, TLS) bubble up as the
 * underlying Ktor / IO exceptions; the caller passes them into
 * [ConnectErrorClassifier] to produce a stable [ConnectError].
 */
sealed class ComfyHttpException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** HTTP server returned a non-2xx status. */
    class HttpStatus(
        val statusCode: Int,
        val responseBody: String? = null,
    ) : ComfyHttpException(
        message = "HTTP $statusCode" + (responseBody?.let { ": ${it.take(256)}" } ?: "")
    )

    /** A 2xx response was received but parsing the body failed. */
    class MalformedResponse(
        val endpoint: String,
        val reason: String,
        cause: Throwable? = null,
    ) : ComfyHttpException("Malformed response from $endpoint: $reason", cause)

    /** A required field was absent from a successful response. */
    class MissingField(
        val endpoint: String,
        val field: String,
    ) : ComfyHttpException("Response from $endpoint is missing required field: $field")
}
