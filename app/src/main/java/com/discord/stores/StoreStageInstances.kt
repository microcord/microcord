package com.discord.stores

import com.discord.api.stageinstance.StageInstance

class StoreStageInstances {
    private val stageInstancesByChannel = linkedMapOf<Long, StageInstance>()
    private var stageInstancesByChannelSnapshot: Map<Long, StageInstance> = emptyMap()
    private val stageInstancesByGuild = linkedMapOf<Long, MutableMap<Long, StageInstance>>()
    private var stageInstancesByGuildSnapshot: Map<Long, Map<Long, StageInstance>> = emptyMap()

    fun getStageInstanceForChannel(channelId: Long) = stageInstancesByChannelSnapshot[channelId]
    fun getStageInstanceForChannelInternal(channelId: Long) = stageInstancesByChannel[channelId]
    fun getStageInstances(): Map<Long, StageInstance> = stageInstancesByChannelSnapshot
    fun getStageInstancesByGuild(): Map<Long, Map<Long, StageInstance>> = stageInstancesByGuildSnapshot
    fun getStageInstancesForGuild(guildId: Long) = stageInstancesByGuildSnapshot[guildId].orEmpty()
    fun getStageInstancesForGuildInternal(guildId: Long): Map<Long, StageInstance> =
        stageInstancesByGuild[guildId].orEmpty()

    fun handleStageInstanceCreate(stageInstance: StageInstance) = put(stageInstance)
    fun handleStageInstanceUpdate(stageInstance: StageInstance) = put(stageInstance)

    fun handleStageInstanceDelete(stageInstance: StageInstance) {
        stageInstancesByChannel.remove(stageInstance.channelId)
        stageInstancesByGuild[stageInstance.guildId]?.remove(stageInstance.channelId)
        snapshotData()
    }

    fun snapshotData() {
        stageInstancesByChannelSnapshot = HashMap(stageInstancesByChannel)
        stageInstancesByGuildSnapshot = stageInstancesByGuild.mapValues { HashMap(it.value) }
    }

    private fun put(stageInstance: StageInstance) {
        stageInstancesByChannel[stageInstance.channelId] = stageInstance
        stageInstancesByGuild.getOrPut(stageInstance.guildId, ::linkedMapOf)[stageInstance.channelId] = stageInstance
        snapshotData()
    }
}
