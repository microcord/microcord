package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isTextOrVoiceChannel
import com.discord.api.channel.isThread
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelPayload
import com.discord.models.message.Message
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.util.Collections
import rx.Observable

/**
 * The newest known message id per channel.
 *
 * This is the client's answer to "is there anything new here", and it has to be maintained from
 * several sources because no single one is complete: `READY` and `GUILD_CREATE` carry
 * `last_message_id` per channel, `MESSAGE_CREATE` carries live messages, and
 * `CHANNEL_UNREAD_UPDATE` carries newer ids for channels the client is not currently subscribed
 * to. Missing any one of them leaves stale channels looking read.
 *
 * The map only ever moves forward — see [updateMostRecentIds] — so an out-of-order or replayed
 * event cannot make a channel look older than it is.
 */
class StoreMessagesMostRecent @JvmOverloads constructor(
    private val observationDeck: ObservationDeck,
    private val cache: Persister<Map<Long, Long>> = Persister(MOST_RECENT_MESSAGE_IDS, emptyMap()),
    /**
     * Resolves a channel's parent, for forum-post propagation. See
     * [updateParentChannelMostRecentIds].
     */
    private val storeChannels: StoreChannels? = null,
) : StoreV2() {
    private val mostRecentIds = HashMap<Long, Long>()
    private var snapshot: Map<Long, Long> = emptyMap()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        mostRecentIdsUpdateFromChannels(payload.privateChannels.orEmpty())
        payload.guilds.orEmpty().forEach { guild ->
            mostRecentIdsUpdateFromChannels(guild.channels.orEmpty())
            mostRecentIdsUpdateFromChannels(guild.threads.orEmpty().filter { it.isThread() })
        }
        markChanged()
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        mostRecentIdsUpdateFromChannels(guild.channels.orEmpty())
        mostRecentIdsUpdateFromChannels(guild.threads.orEmpty().filter { it.isThread() })
    }

    @StoreThread
    fun handleChannelCreateOrUpdate(channel: Channel) = mostRecentIdsUpdateFromChannels(listOf(channel))

    @StoreThread
    fun handleThreadCreateOrUpdate(channel: Channel) {
        if (channel.isThread()) mostRecentIdsUpdateFromChannels(listOf(channel))
    }

    @StoreThread
    fun handleThreadListSync(payload: ModelThreadListSync) =
        mostRecentIdsUpdateFromChannels(payload.threads.filter { it.isThread() })

    /**
     * `CHANNEL_UNREAD_UPDATE`: newer ids for channels the client has no live subscription to.
     *
     * Without this, a guild the user is not viewing never advances and its channels stay
     * incorrectly marked as read.
     */
    @StoreThread
    fun handleChannelUnreadUpdate(channelReadStateUpdate: ModelChannelUnreadUpdate) {
        channelReadStateUpdate.channelReadStates.forEach { readState ->
            if (updateMostRecentIds(readState.channelId, readState.lastMessageId)) markChanged()
        }
    }

    @StoreThread
    fun handleMessageCreate(message: Message) = handleMessageCreate(message.channelId, message.id)

    @StoreThread
    fun handleMessageCreate(message: com.discord.api.message.Message) =
        handleMessageCreate(message.channelId, message.id)

    @StoreThread
    fun handleMessageCreate(channelId: Long, messageId: Long) {
        if (updateMostRecentIds(channelId, messageId)) markChanged()
    }

    fun getMostRecentIds(): Map<Long, Long> = snapshot

    @StoreThread
    internal fun getMostRecentIdsInternal(): Map<Long, Long> = mostRecentIds

    fun observeRecentMessageIds(): Observable<Map<Long, Long>> =
        observationDeck.connectRx(this) { snapshot }.distinctUntilChanged()

    fun observeRecentMessageIds(channelId: Long): Observable<Long> =
        observeRecentMessageIds().map { it[channelId] ?: 0L }.distinctUntilChanged()

    override fun snapshotData() {
        snapshot = Collections.unmodifiableMap(HashMap(mostRecentIds))
        cache.set(HashMap(snapshot))
    }

    /**
     * Folds a batch of channels in.
     *
     * Only text and voice channels are considered: categories carry a meaningless
     * `last_message_id` and would give their guild a phantom unread channel.
     */
    @StoreThread
    private fun mostRecentIdsUpdateFromChannels(channels: Collection<Channel>) {
        channels.forEach { channel ->
            if (!channel.isTextOrVoiceChannel()) return@forEach
            val updated = updateMostRecentIds(channel.id, channel.lastMessageId)
            val parentUpdated = updateParentChannelMostRecentIds(channel)
            if (updated || parentUpdated) markChanged()
        }
    }

    /**
     * A new forum post makes its parent forum look new.
     *
     * A forum channel has no messages of its own — its posts are threads — so without this a
     * forum never shows as unread no matter how much activity it has. Archived posts are excluded
     * because they cannot receive new activity.
     */
    @StoreThread
    private fun updateParentChannelMostRecentIds(channel: Channel): Boolean {
        if (!channel.isThread() || channel.threadMetadata?.archived == true) return false
        val parent = storeChannels?.getChannel(channel.parentId) ?: return false
        if (!parent.isThreadOnlyChannel()) return false
        return updateMostRecentIds(parent.id, channel.id)
    }

    /**
     * Monotonic write: returns whether the id actually moved forward.
     *
     * Snowflakes are time-ordered, so a plain numeric comparison is the ordering; the reference
     * routes this through `MessageUtils.compareMessages`, which is a null-safe `compare` on the
     * same numbers (verified against the DEX — it has no special handling for local or negative
     * ids, so the natural `Long` order used here is equivalent). That utility belongs in
     * `utilities/message/MessageUtils`, which is owned by another slice and does not exist yet.
     */
    @StoreThread
    private fun updateMostRecentIds(channelId: Long, messageId: Long): Boolean {
        if (channelId <= 0L) return false
        if (compareMessages(mostRecentIds[channelId], messageId) >= 0) return false
        mostRecentIds[channelId] = messageId
        return true
    }

    companion object {
        const val MOST_RECENT_MESSAGE_IDS = "MOST_RECENT_MESSAGE_IDS"

        /** Reference `MessageUtils.compareMessages`: null-safe sign comparison of two ids. */
        private fun compareMessages(first: Long?, second: Long?): Int =
            (first ?: 0L).compareTo(second ?: 0L)
    }
}
