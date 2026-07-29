package com.discord.widgets.chat.list

import com.discord.stores.StoreExperiments
import com.discord.stores.StoreStream

class ViewThreadsFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
) {
    fun isEnabled(): Boolean =
        storeExperiments.getUserExperiment(EXPERIMENT_NAME, true)?.bucket == 1

    companion object {
        private const val EXPERIMENT_NAME = "2021-02_view_threads"
        val INSTANCE: ViewThreadsFeatureFlag by lazy(::ViewThreadsFeatureFlag)

        fun isEnabled(): Boolean = INSTANCE.isEnabled()
    }
}
