package com.discord.stores

import android.content.SharedPreferences
import androidx.core.content.edit
import com.discord.utilities.cache.SharedPreferencesProvider

/**
 * Reference `stores/DeveloperOptionsStore`.
 *
 * Staff-only request switches. Each is a boolean, and the enabled ones are sent as a
 * comma-separated `X-Debug-Options` header, which is what tells the backend to serve a request from
 * a different deployment.
 *
 * Local only, and deliberately so: this is per-device debugging state, not an account setting, and
 * syncing it would follow the account onto devices that were never meant to be pointed at canary.
 */
class StoreDebugOptions(
    private val preferences: SharedPreferences = SharedPreferencesProvider.getPrefsSessionDurable(),
) {

    /** Every switch the header understands, keyed by the token the backend expects. */
    enum class Option(val headerValue: String) {
        CANARY("canary"),
    }

    fun isEnabled(option: Option): Boolean = preferences.getBoolean(key(option), false)

    fun setEnabled(option: Option, enabled: Boolean) {
        preferences.edit { putBoolean(key(option), enabled) }
    }

    /**
     * The `X-Debug-Options` value, or null when nothing is on.
     *
     * Null rather than an empty string: an empty header is still a header, and sending one on every
     * request from every user would be a needless change to what the backend sees.
     */
    fun getDebugOptionsHeaderValue(): String? = Option.entries
        .filter(::isEnabled)
        .joinToString(",") { it.headerValue }
        .takeIf { it.isNotEmpty() }

    private fun key(option: Option) = "$KEY_PREFIX${option.name}"

    private companion object {
        const val KEY_PREFIX = "DEBUG_OPTION_"
    }
}
