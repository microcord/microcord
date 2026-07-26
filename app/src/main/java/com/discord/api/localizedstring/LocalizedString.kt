package com.discord.api.localizedstring

import java.io.Serializable

/**
 * A server-supplied string with per-locale translations.
 *
 * [default] is used whenever the viewer's locale has no entry in [localizations].
 */
data class LocalizedString(
    val default: String,
    val localizations: Map<String, String>,
) : Serializable
