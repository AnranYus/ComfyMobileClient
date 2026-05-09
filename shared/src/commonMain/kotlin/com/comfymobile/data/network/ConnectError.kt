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

    /**
     * State machine asked the runner to perform a side effect
     * (e.g. `OpenWs`, `PollHistory`) while no active server is
     * selected (`ActiveServerHolder.current.value == null`). The
     * runner emits this **without** routing to a default URL or
     * the previously-active server, so the UI surfaces an explicit
     * "pick a server" message instead of silently retrying against
     * the wrong endpoint.
     *
     * Per @Ores `b522a9f3` / @Lily `60a7e64a` (PR #18 thread): this
     * is a first-class user-facing error, not a generic UNKNOWN —
     * separating it keeps copy and analytics distinguishable.
     */
    NO_ACTIVE_SERVER,

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
