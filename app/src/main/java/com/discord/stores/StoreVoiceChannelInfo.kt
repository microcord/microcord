package com.discord.stores

import com.discord.api.channel.ChannelInfo
import com.discord.api.channel.VoiceChannelStartTimeUpdate
import com.discord.api.channel.VoiceChannelStatusUpdate
import com.discord.stores.updates.ObservationDeck
import rx.Observable

/**
 * Voice channel statuses and session start times. Neither is part of the channel object: the
 * gateway sends them per guild on request (`REQUEST_CHANNEL_INFO`) and then pushes changes, so a
 * guild is asked once per connection, the first time its channel list is shown.
 */
class StoreVoiceChannelInfo(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
    /** `StoreGatewayConnection.requestChannelInfo`; false while the session is not established. */
    private val requestOverGateway: (guildId: Long) -> Boolean,
) : StoreV2() {

    data class Info(val status: String?, val startTimeMs: Long?)

    private val infos = HashMap<Long, Info>()
    private val guildIds = HashMap<Long, Long>()
    private var infosSnapshot: Map<Long, Info> = emptyMap()
    private val requestedGuildIds = HashSet<Long>()

    fun observeInfo(): Observable<Map<Long, Info>> =
        observationDeck.connectRx(this) { infosSnapshot }.distinctUntilChanged()

    fun fetchChannelInfo(guildId: Long) = dispatcher.schedule {
        if (guildId > 0L && guildId !in requestedGuildIds && requestOverGateway(guildId)) {
            requestedGuildIds.add(guildId)
        }
    }

    @StoreThread
    fun handleConnectionOpen() {
        requestedGuildIds.clear()
    }

    @StoreThread
    fun handleChannelInfo(channelInfo: ChannelInfo) {
        // The reply is the guild's whole state: anything it leaves out has no status or session.
        val listed = channelInfo.channels.associateBy { it.id }
        guildIds.filterValues { it == channelInfo.guildId }.keys
            .filterNot(listed::containsKey)
            .forEach { update(it, channelInfo.guildId) { EMPTY } }
        listed.values.forEach { channel ->
            update(channel.id, channelInfo.guildId) { Info(channel.status, channel.voiceStartTime?.let(::secondsToMs)) }
        }
    }

    @StoreThread
    fun handleStatusUpdate(update: VoiceChannelStatusUpdate) =
        update(update.id, update.guildId) { it.copy(status = update.status) }

    @StoreThread
    fun handleStartTimeUpdate(update: VoiceChannelStartTimeUpdate) =
        update(update.id, update.guildId) { it.copy(startTimeMs = update.voiceStartTime?.let(::secondsToMs)) }

    @StoreThread
    fun reset() {
        requestedGuildIds.clear()
        guildIds.clear()
        if (infos.isEmpty()) return
        infos.clear()
        markChanged()
    }

    private fun update(channelId: Long, guildId: Long, transform: (Info) -> Info) {
        val next = transform(infos[channelId] ?: EMPTY).takeUnless { it == EMPTY }
        if (next == infos[channelId]) return
        if (next == null) {
            infos.remove(channelId)
            guildIds.remove(channelId)
        } else {
            infos[channelId] = next
            guildIds[channelId] = guildId
        }
        markChanged()
    }

    override fun snapshotData() {
        infosSnapshot = HashMap(infos)
    }

    private companion object {
        val EMPTY = Info(null, null)
        fun secondsToMs(seconds: Long) = seconds * 1000L
    }
}
