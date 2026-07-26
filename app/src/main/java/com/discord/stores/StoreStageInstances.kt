package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.stageinstance.StageInstance
import com.discord.stores.updates.ObservationDeck
import rx.Observable

class StoreStageInstances(
    private val observationDeck: ObservationDeck,
) : StoreV2() {
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

    /**
     * Emits `null` when the channel has no live stage, because ending a stage is exactly what
     * subscribers need to react to — filtering nulls out would leave the stage UI up forever.
     */
    fun observeStageInstanceForChannel(channelId: Long): Observable<StageInstance?> =
        observationDeck.connectRx(this) { getStageInstanceForChannel(channelId) }
    fun observeStageInstances(): Observable<Map<Long, StageInstance>> =
        observationDeck.connectRx(this, generator = ::getStageInstances)
    fun observeStageInstancesForGuild(guildId: Long): Observable<Map<Long, StageInstance>> =
        observationDeck.connectRx(this, generator = { getStageInstancesForGuild(guildId) })

    @StoreThread
    fun handleConnectionOpen(guilds: List<Guild>) {
        StoreThreadScheduler.assertCurrentThread()
        stageInstancesByChannel.clear()
        stageInstancesByGuild.clear()
        guilds.forEach(::handleGuildAdd)
        markChanged()
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) = guild.stageInstances.orEmpty().forEach(::put)

    @StoreThread
    fun handleGuildRemove(guild: Guild) {
        val channels = stageInstancesByGuild.remove(guild.id)?.keys.orEmpty()
        stageInstancesByChannel.keys.removeAll(channels)
        markChanged()
    }

    @StoreThread
    fun handleChannelDelete(channel: Channel) = delete(channel.guildId, channel.id)

    @StoreThread
    fun handleStageInstanceCreate(stageInstance: StageInstance) = put(stageInstance)
    @StoreThread
    fun handleStageInstanceUpdate(stageInstance: StageInstance) = put(stageInstance)

    @StoreThread
    fun handleStageInstanceDelete(stageInstance: StageInstance) {
        delete(stageInstance.guildId, stageInstance.channelId)
    }

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        stageInstancesByChannel.clear()
        stageInstancesByGuild.clear()
        markChanged()
    }

    override fun snapshotData() {
        stageInstancesByChannelSnapshot = HashMap(stageInstancesByChannel)
        stageInstancesByGuildSnapshot = stageInstancesByGuild.mapValues { HashMap(it.value) }
    }

    private fun put(stageInstance: StageInstance) {
        StoreThreadScheduler.assertCurrentThread()
        stageInstancesByChannel[stageInstance.channelId] = stageInstance
        stageInstancesByGuild.getOrPut(stageInstance.guildId, ::linkedMapOf)[stageInstance.channelId] = stageInstance
        markChanged()
    }

    private fun delete(guildId: Long?, channelId: Long) {
        StoreThreadScheduler.assertCurrentThread()
        stageInstancesByChannel.remove(channelId)
        stageInstancesByGuild[guildId]?.remove(channelId)
        markChanged()
    }
}
