package com.discord.utilities.feature

import com.discord.utilities.cache.SharedPreferencesProvider

object FeatureFlags {
    private const val VOICE_MESSAGES = "feature_voice_messages"
    private const val TAP_TAP = "feature_tap_tap"
    private const val FETCH_UNCACHED_USERS = "feature_fetch_uncached_users"

    fun voiceMessagesEnabled() = enabled(VOICE_MESSAGES, true)
    fun setVoiceMessagesEnabled(enabled: Boolean) = setEnabled(VOICE_MESSAGES, enabled)
    fun tapTapEnabled() = enabled(TAP_TAP)
    fun setTapTapEnabled(enabled: Boolean) = setEnabled(TAP_TAP, enabled)
    fun fetchUncachedUsersEnabled() = enabled(FETCH_UNCACHED_USERS)
    fun setFetchUncachedUsersEnabled(enabled: Boolean) = setEnabled(FETCH_UNCACHED_USERS, enabled)

    private fun enabled(key: String, default: Boolean = false) =
        SharedPreferencesProvider.getOrNull()?.getBoolean(key, default) ?: default

    private fun setEnabled(key: String, enabled: Boolean) =
        SharedPreferencesProvider.get().edit().putBoolean(key, enabled).apply()
}
