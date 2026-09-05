package com.discord.utilities.feature

import com.discord.utilities.cache.SharedPreferencesProvider

enum class GifProvider { KLIPY, TENOR, GIPHY }

object GifProviderSettings {
    fun get(): GifProvider {
        if (!FeatureFlags.moreGifProvidersEnabled()) return GifProvider.KLIPY
        return ClientSettings.gifProvider.get(SharedPreferencesProvider.get())
            ?.let { runCatching { GifProvider.valueOf(it) }.getOrNull() }
            ?: GifProvider.KLIPY
    }

    fun set(provider: GifProvider) {
        ClientSettings.gifProvider.set(SharedPreferencesProvider.get(), provider.name)
    }
}
