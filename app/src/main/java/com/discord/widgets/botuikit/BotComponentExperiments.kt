package com.discord.widgets.botuikit

import com.discord.api.botuikit.ComponentType
import com.discord.stores.StoreExperiments

class BotComponentExperiments(
    val storeExperiments: StoreExperiments,
) : ComponentExperiments {
    override fun isEnabled(componentType: ComponentType): Boolean = componentType != ComponentType.UNKNOWN

    fun isExperimentEnabled(experimentName: String): Boolean =
        storeExperiments.getUserExperiment(experimentName, false)?.bucket == 1

    companion object {
        var botComponentExperiments: BotComponentExperiments? = null
        private val defaults = object : ComponentExperiments {
            override fun isEnabled(componentType: ComponentType) = componentType != ComponentType.UNKNOWN
        }

        fun get(storeExperiments: StoreExperiments): ComponentExperiments =
            botComponentExperiments
                ?: BotComponentExperiments(storeExperiments).also { botComponentExperiments = it }

        fun getOrDefault(): ComponentExperiments = botComponentExperiments ?: defaults
    }
}
