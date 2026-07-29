package com.discord.widgets.forums

import com.discord.utilities.cache.SharedPreferencesProvider

object ForumGuidelinesManager {
    private const val CACHE_KEY = "CACHE_KEY_USER_VIEWED_FORUM_GUIDELINES"

    fun hasSeenGuidelines(channelId: Long): Boolean = channelId.toString() in
        SharedPreferencesProvider.get().getStringSet(CACHE_KEY, emptySet()).orEmpty()

    fun markGuidelinesSeen(channelId: Long) {
        val seen = SharedPreferencesProvider.get().getStringSet(CACHE_KEY, emptySet()).orEmpty() + channelId.toString()
        SharedPreferencesProvider.get().edit().putStringSet(CACHE_KEY, seen).apply()
    }
}
