package com.discord.utilities.feature

import com.discord.utilities.cache.SharedPreferencesProvider

enum class GifProvider { KLIPY, TENOR, GIPHY }

object GifProviderSettings {
    private const val KEY = "feature_gif_provider"

    fun get(): GifProvider {
        if (!FeatureFlags.moreGifProvidersEnabled()) return GifProvider.KLIPY
        return SharedPreferencesProvider.get().getString(KEY, null)
            ?.let { runCatching { GifProvider.valueOf(it) }.getOrNull() }
            ?: GifProvider.KLIPY
    }

    fun set(provider: GifProvider) {
        SharedPreferencesProvider.get().edit().putString(KEY, provider.name).apply()
    }
}
