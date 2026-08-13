package com.discord.utilities.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager

object SharedPreferencesProvider {
    private var sharedPreferences: SharedPreferences? = null
    private var prefsSessionDurable: SharedPreferences? = null

    fun init(context: Context) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        prefsSessionDurable = context.getSharedPreferences("prefsSessionDurable", Context.MODE_PRIVATE)
    }

    fun get(): SharedPreferences =
        checkNotNull(sharedPreferences) { "You must call init() before accessing prefs" }

    /** For callers whose setting is optional enough that missing prefs are not worth crashing over. */
    fun getOrNull(): SharedPreferences? = sharedPreferences

    fun getPrefsSessionDurable(): SharedPreferences =
        checkNotNull(prefsSessionDurable) { "You must call init() before accessing prefsSessionDurable" }

    /**
     * Drops everything the signed-in account owns, keeping settings that describe the device.
     *
     * The reference splits this by store — `UserAgnosticStore` opts out of its user-data clear — but
     * one preference file backs both kinds here, so the split has to be by key. An allowlist rather
     * than a blocklist because an unrecognised key is then wiped, and a device setting lost on a
     * switch is a far cheaper mistake than one account reading another's state.
     */
    fun clearUserScoped() {
        val prefs = get()
        val kept = prefs.all.filterKeys(::isDeviceScoped)
        prefs.edit().clear().apply {
            kept.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.apply()
    }

    private fun isDeviceScoped(key: String): Boolean =
        key in DEVICE_SCOPED_KEYS || DEVICE_SCOPED_PREFIXES.any(key::startsWith)

    private val DEVICE_SCOPED_PREFIXES = listOf(
        "CACHE_KEY_VOICE_SETTINGS_",
        "feature_",
    )

    private val DEVICE_SCOPED_KEYS = setOf(
        "CACHE_KEY_THEME",
        "CACHE_KEY_THEME_SYNC",
        "CACHE_KEY_LOCALE",
        "CACHE_KEY_LOCALE_SYNC",
        "CACHE_KEY_FONT_SCALE",
        "CACHE_KEY_INLINE_ATTACHMENT_MEDIA",
        "CACHE_KEY_IMAGE_COMPRESSION",
        "CACHE_KEY_USE_CHROME_CUSTOM_TABS",
        "CACHE_KEY_DEVELOPER_MODE",
        "CACHE_KEY_INLINE_EMBED_MEDIA",
        "CACHE_KEY_MOBILE_OVERLAY",
        "CACHE_KEY_RENDER_EMBEDS",
        "CACHE_KEY_SHIFT_ENTER_TO_SEND",
        "CACHE_KEY_SYNC_TEXT_AND_IMAGES",
        "CACHE_KEY_HIDE_VOICE_PARTICIPANTS",
        "screenshot_detection_enabled",
    )

    @VisibleForTesting
    fun setInstanceForTesting(prefs: SharedPreferences?, sessionDurable: SharedPreferences? = prefs) {
        sharedPreferences = prefs
        prefsSessionDurable = sessionDurable
    }
}
