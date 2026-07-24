package com.discord.stores

class StoreStream private constructor() {
    companion object {
        private val stageInstances = StoreStageInstances()

        fun getStageInstances() = stageInstances
    }
}
