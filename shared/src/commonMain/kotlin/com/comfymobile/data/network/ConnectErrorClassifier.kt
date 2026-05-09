package com.comfymobile.data.network

/**
 * Pure mapper from low-level connection-attempt outcomes to
 * [ConnectError] categories. Lives in commonMain as a function and
 * not tied to any particular HTTP client so it can be shared by
 * the connect-flow UI, the WebSocket reconnect logic, and tests.
 *
 * Callers describe what happened with [ConnectAttemptOutcome] —
 * either a thrown error (network layer) or an HTTP response
 * (probe layer) — and get back a stable enum + optional context.
 *
 * The keyword set used to recognise platform errors deliberately
 * stays small and string-based so it works on both JVM (Android,
 * via OkHttp/IOException messages) and Kotlin/Native iOS (via
 * Darwin's NSURLError descriptions); both surface the same English
 * substrings ("timeout", "refused", "ssl handshake"…). When that
 * heuristic is not enough callers should pass [ConnectAttemptOutcome.platformErrorTag]
 * explicitly to short-circuit classification.
 */
object ConnectErrorClassifier {

    fun classify(outcome: ConnectAttemptOutcome): Pair<ConnectError, ConnectErrorContext> =
        when (outcome) {
            is ConnectAttemptOutcome.FormatRejected ->
                ConnectError.FORMAT to ConnectErrorContext(description = outcome.reason)

            is ConnectAttemptOutcome.NetworkFailure -> classifyNetwork(outcome)

            is ConnectAttemptOutcome.HttpResponse -> classifyHttp(outcome)

            ConnectAttemptOutcome.UnknownFailure ->
                ConnectError.UNKNOWN to ConnectErrorContext()
        }

    private fun classifyNetwork(outcome: ConnectAttemptOutcome.NetworkFailure):
        Pair<ConnectError, ConnectErrorContext> {
        outcome.platformErrorTag?.let {
            return when (it) {
                ConnectAttemptOutcome.PlatformErrorTag.Timeout ->
                    ConnectError.TIMEOUT to ConnectErrorContext(description = outcome.message)
                ConnectAttemptOutcome.PlatformErrorTag.Refused ->
                    ConnectError.REFUSED to ConnectErrorContext(description = outcome.message)
                ConnectAttemptOutcome.PlatformErrorTag.TlsHandshake ->
                    ConnectError.TLS_HANDSHAKE to ConnectErrorContext(description = outcome.message)
                ConnectAttemptOutcome.PlatformErrorTag.Unknown ->
                    ConnectError.UNKNOWN to ConnectErrorContext(description = outcome.message)
            }
        }
        // Fallback: substring sniff on the message. Conservative — any
        // unknown wording falls through to UNKNOWN.
        val lower = outcome.message?.lowercase().orEmpty()
        return when {
            "timeout" in lower || "timed out" in lower ->
                ConnectError.TIMEOUT to ConnectErrorContext(description = outcome.message)
            "refused" in lower || "econnrefused" in lower ->
                ConnectError.REFUSED to ConnectErrorContext(description = outcome.message)
            "ssl" in lower || "tls" in lower || "handshake" in lower ->
                ConnectError.TLS_HANDSHAKE to ConnectErrorContext(description = outcome.message)
            else -> ConnectError.UNKNOWN to ConnectErrorContext(description = outcome.message)
        }
    }

    private fun classifyHttp(outcome: ConnectAttemptOutcome.HttpResponse):
        Pair<ConnectError, ConnectErrorContext> {
        // /system_stats probe: 404 means we hit *some* server but the
        // ComfyUI path is not there.
        if (outcome.statusCode == 404) {
            return ConnectError.WRONG_PORT_404 to ConnectErrorContext(statusCode = 404)
        }
        // 200 but body did not look like ComfyUI's system_stats schema.
        if (outcome.statusCode in 200..299 && !outcome.bodyLooksLikeComfyUi) {
            return ConnectError.NOT_COMFYUI to ConnectErrorContext(
                statusCode = outcome.statusCode,
                description = "system_stats response did not match expected schema",
            )
        }
        // Other non-2xx response — bucket as UNKNOWN with status code
        // so UX can render "couldn't connect (HTTP $code)".
        if (outcome.statusCode !in 200..299) {
            return ConnectError.UNKNOWN to ConnectErrorContext(statusCode = outcome.statusCode)
        }
        // 200 + recognised → not actually an error; caller should not
        // have invoked the classifier in that case, but be safe.
        return ConnectError.UNKNOWN to ConnectErrorContext(statusCode = outcome.statusCode)
    }
}

/**
 * Description of one connection-attempt outcome, suitable for
 * feeding into [ConnectErrorClassifier]. Pure data; no live
 * connections / Throwables held.
 */
sealed interface ConnectAttemptOutcome {

    /** The user-supplied host:port did not pass form validation. */
    data class FormatRejected(val reason: String) : ConnectAttemptOutcome

    /** The TCP / TLS layer failed before any HTTP exchange. */
    data class NetworkFailure(
        val message: String? = null,
        val platformErrorTag: PlatformErrorTag? = null,
    ) : ConnectAttemptOutcome

    /** A complete HTTP response came back; status + a heuristic
     *  whether the body parsed as `/system_stats`. */
    data class HttpResponse(
        val statusCode: Int,
        val bodyLooksLikeComfyUi: Boolean,
    ) : ConnectAttemptOutcome

    /** Total mystery; classifier returns [ConnectError.UNKNOWN]. */
    data object UnknownFailure : ConnectAttemptOutcome

    /**
     * Optional explicit hint from the platform layer when it can
     * identify the cause precisely (e.g. iOS NSURLErrorTimedOut →
     * Timeout). Avoids depending on string sniffing.
     */
    enum class PlatformErrorTag { Timeout, Refused, TlsHandshake, Unknown }
}
