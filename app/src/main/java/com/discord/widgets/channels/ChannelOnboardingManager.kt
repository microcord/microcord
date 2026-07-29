package com.discord.widgets.channels

import com.discord.utilities.cache.SharedPreferencesProvider

class ChannelOnboardingManager {
    fun hasUserSeenChannelOnboarding(): Boolean =
        SharedPreferencesProvider.get().getBoolean(CACHE_KEY_CHANNEL_ONBOARDED, false)

    fun markSeenUserChannelOnboarding() {
        SharedPreferencesProvider.get().edit().putBoolean(CACHE_KEY_CHANNEL_ONBOARDED, true).apply()
    }

    private companion object {
        const val CACHE_KEY_CHANNEL_ONBOARDED = "CACHE_KEY_CHANNEL_ONBOARDED"
    }
}
