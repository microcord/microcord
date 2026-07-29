package com.discord.widgets.settings.premium

import com.discord.stores.StoreExperiments

class OutboundPromosPreviewFeatureFlag(
    private val storeExperiments: StoreExperiments,
) {
    fun isEnabled(): Boolean =
        storeExperiments.getUserExperiment(EXPERIMENT_NAME, true)?.bucket == ENABLED_BUCKET

    private companion object {
        const val EXPERIMENT_NAME = "2021-06_preview_promotions"
        const val ENABLED_BUCKET = 1
    }
}
