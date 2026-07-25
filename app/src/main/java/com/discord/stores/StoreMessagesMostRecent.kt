package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.util.Collections
import rx.Observable

class StoreMessagesMostRecent(
    private val observationDeck: ObservationDeck,
    private val cache: Persister<Map<Long, Long>> = Persister(MOST_RECENT_MESSAGE_IDS, emptyMap()),
) : StoreV2() {
    private val mostRecentIds = HashMap<Long, Long>()
    private var snapshot: Map<Long, Long> = emptyMap()

    @StoreThread fun restore() { mostRecentIds.putAll(cache.get()); markChanged() }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.privateChannels.orEmpty().forEach(::handleChannelCreateOrUpdate)
        payload.guilds.orEmpty().forEach { guild ->
            guild.channels.orEmpty().forEach(::handleChannelCreateOrUpdate)
            guild.threads.orEmpty().forEach(::handleChannelCreateOrUpdate)
        }
    }

    @StoreThread fun handleChannelCreateOrUpdate(channel: Channel) = update(channel.id, channel.lastMessageId)
    @StoreThread fun handleMessageCreate(message: Message) = update(message.channelId, message.id)
    @StoreThread fun handleMessageCreate(message: com.discord.api.message.Message) = update(message.channelId, message.id)
    @StoreThread fun handleMessageCreate(channelId: Long, messageId: Long) = update(channelId, messageId)

    @StoreThread
    fun handlePreLogout() {
        mostRecentIds.clear()
        cache.clear(persist = true)
        markChanged()
    }

    fun getMostRecentIds(): Map<Long, Long> = snapshot
    internal fun getMostRecentIdsInternal(): Map<Long, Long> = mostRecentIds
    fun observeRecentMessageIds(): Observable<Map<Long, Long>> = observationDeck.connectRx(this) { snapshot }.distinctUntilChanged()
    fun observeRecentMessageIds(channelId: Long): Observable<Long> =
        observeRecentMessageIds().map { it[channelId] ?: 0L }.distinctUntilChanged()

    override fun snapshotData() {
        snapshot = Collections.unmodifiableMap(HashMap(mostRecentIds))
        cache.set(HashMap(snapshot))
    }

    private fun update(channelId: Long, messageId: Long) {
        if (channelId <= 0 || messageId <= (mostRecentIds[channelId] ?: 0L)) return
        mostRecentIds[channelId] = messageId
        markChanged()
    }

    companion object { const val MOST_RECENT_MESSAGE_IDS = "MOST_RECENT_MESSAGE_IDS" }
}
