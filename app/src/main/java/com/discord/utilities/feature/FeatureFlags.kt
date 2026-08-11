package com.discord.utilities.feature

import com.discord.utilities.cache.SharedPreferencesProvider

object FeatureFlags {
    private const val VOICE_MESSAGES = "feature_voice_messages"
    private const val TAP_TAP = "feature_tap_tap"
    private const val FETCH_UNCACHED_USERS = "feature_fetch_uncached_users"
    private const val VIEW_PROFILE_IMAGES = "feature_view_profile_images"
    private const val BETTER_CHATBOX = "feature_better_chatbox"
    private const val AVATAR_DECORATIONS = "feature_avatar_decorations"
    private const val NAMEPLATES = "feature_nameplates"
    private const val PROFILE_EFFECTS = "feature_profile_effects"
    private const val PROFILE_FRAMES = "feature_profile_frames"

    fun voiceMessagesEnabled() = enabled(VOICE_MESSAGES, true)
    fun setVoiceMessagesEnabled(enabled: Boolean) = setEnabled(VOICE_MESSAGES, enabled)
    fun tapTapEnabled() = enabled(TAP_TAP)
    fun setTapTapEnabled(enabled: Boolean) = setEnabled(TAP_TAP, enabled)
    fun fetchUncachedUsersEnabled() = enabled(FETCH_UNCACHED_USERS)
    fun setFetchUncachedUsersEnabled(enabled: Boolean) = setEnabled(FETCH_UNCACHED_USERS, enabled)
    fun viewProfileImagesEnabled() = enabled(VIEW_PROFILE_IMAGES)
    fun setViewProfileImagesEnabled(enabled: Boolean) = setEnabled(VIEW_PROFILE_IMAGES, enabled)
    fun betterChatboxEnabled() = enabled(BETTER_CHATBOX)
    fun setBetterChatboxEnabled(enabled: Boolean) = setEnabled(BETTER_CHATBOX, enabled)
    fun avatarDecorationsEnabled() = enabled(AVATAR_DECORATIONS, true)
    fun setAvatarDecorationsEnabled(enabled: Boolean) = setEnabled(AVATAR_DECORATIONS, enabled)
    fun nameplatesEnabled() = enabled(NAMEPLATES, true)
    fun setNameplatesEnabled(enabled: Boolean) = setEnabled(NAMEPLATES, enabled)
    fun profileEffectsEnabled() = enabled(PROFILE_EFFECTS, true)
    fun setProfileEffectsEnabled(enabled: Boolean) = setEnabled(PROFILE_EFFECTS, enabled)
    fun profileFramesEnabled() = enabled(PROFILE_FRAMES, true)
    fun setProfileFramesEnabled(enabled: Boolean) = setEnabled(PROFILE_FRAMES, enabled)

    private fun enabled(key: String, default: Boolean = false) =
        SharedPreferencesProvider.getOrNull()?.getBoolean(key, default) ?: default

    private fun setEnabled(key: String, enabled: Boolean) =
        SharedPreferencesProvider.get().edit().putBoolean(key, enabled).apply()
}
