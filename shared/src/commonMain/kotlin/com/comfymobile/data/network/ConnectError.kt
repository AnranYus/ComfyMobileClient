package com.comfymobile.data.network

/**
 * Technical reason a connection attempt or live connection failed.
 *
 * Strictly an enum of *categories* — no user-facing copy. T1.4 owns
 * the mapping from these categories to localized strings (per the
 * seam contract in #ComfyMobile:af119226 msg `1fa6de6f`).
 */
enum class ConnectError {

    /** Host:port format did not pass the local form regex; never hit
     * the network. */
    FORMAT,

    /** TCP connect timed out. */
    TIMEOUT,

    /** TCP connect refused (port closed / firewall). */
    REFUSED,

    /** TLS / SSL handshake failed. We never expect TLS on LAN, but
     * users may type `https://` by accident. */
    TLS_HANDSHAKE,

    /** Reached an HTTP server that responded but did not look like
     * ComfyUI (`/system_stats` non-parseable). */
    NOT_COMFYUI,

    /** Reached an HTTP server but the ComfyUI path was not there
     * (`/system_stats` returned 404). */
    WRONG_PORT_404,

    /** Catch-all bucket for unexpected throwables. UX still has to
     * render *something*. */
    UNKNOWN,
}

/**
 * Optional context attached to a [ConnectError] when we have it.
 * Pure data — no exceptions held inside `cause` for cross-platform
 * serialisation safety; just a description.
 */
data class ConnectErrorContext(
    val statusCode: Int? = null,
    val description: String? = null,
)
