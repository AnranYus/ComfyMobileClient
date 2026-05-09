package com.comfymobile.domain.node

import kotlinx.serialization.Serializable

/**
 * Bilingual string used everywhere in the descriptor table.
 *
 * `en` is optional because mobile UI primarily targets Chinese users
 * (per `docs/CONTEXT.md` v4); when absent the display falls back to
 * `zh`. Future locales can be added without changing the schema by
 * convention `{"zh":"...", "en":"...", "ja":"..."}` — kotlinx-
 * serialization tolerates extra keys.
 */
@Serializable
data class LocalizedString(
    val zh: String,
    val en: String? = null,
)
