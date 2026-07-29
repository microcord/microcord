package com.discord.widgets.chat.input.emoji

import com.discord.stores.StoreExperiments
import com.discord.stores.StoreStream

class EmojiAutocompletePremiumUpsellFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
) {
    fun isEnabled(): Boolean =
        (storeExperiments.getUserExperiment(EXPERIMENT_NAME, true)?.bucket ?: 0) != 0

    companion object {
        private const val EXPERIMENT_NAME = "2021-03_nitro_emoji_autocomplete_upsell_android"

        val INSTANCE by lazy { EmojiAutocompletePremiumUpsellFeatureFlag() }
    }
}
