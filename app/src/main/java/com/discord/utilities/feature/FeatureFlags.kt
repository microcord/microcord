package com.discord.utilities.feature

import com.discord.utilities.cache.SharedPreferencesProvider

object FeatureFlags {
    private const val VOICE_MESSAGES = "feature_voice_messages"

    fun voiceMessagesEnabled() =
        SharedPreferencesProvider.getOrNull()?.getBoolean(VOICE_MESSAGES, true) ?: true

    fun setVoiceMessagesEnabled(enabled: Boolean) {
        SharedPreferencesProvider.get().edit().putBoolean(VOICE_MESSAGES, enabled).apply()
    }
}
