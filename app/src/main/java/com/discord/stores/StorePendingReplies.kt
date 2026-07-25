package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.message.MessageReference
import com.discord.models.domain.ModelMessageDelete
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import java.util.Collections
import rx.Observable

class StorePendingReplies(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
) : StoreV2() {
    data class PendingReply(
        val messageReference: MessageReference,
        val originalMessage: Message,
        val shouldMention: Boolean = true,
        val showMentionToggle: Boolean = true,
    )

    private val pendingReplies = HashMap<Long, PendingReply>()
    private var snapshot: Map<Long, PendingReply> = emptyMap()

    fun getPendingReply(channelId: Long): PendingReply? = snapshot[channelId]
    fun observePendingReply(channelId: Long): Observable<PendingReply?> =
        observationDeck.connectRx(this) { getPendingReply(channelId) }.distinctUntilChanged()

    fun onCreatePendingReply(channelId: Long, guildId: Long?, message: Message, shouldMention: Boolean = true, showMentionToggle: Boolean = true) =
        dispatcher.schedule {
            pendingReplies[channelId] = PendingReply(MessageReference(guildId, channelId, message.id), message, shouldMention, showMentionToggle)
            markChanged()
        }

    fun onCreatePendingReply(channel: Channel, message: Message, shouldMention: Boolean = true, showMentionToggle: Boolean = true) =
        onCreatePendingReply(channel.id, channel.guildId.takeUnless { it == 0L }, message, shouldMention, showMentionToggle)

    fun onDeletePendingReply(channelId: Long) = dispatcher.schedule {
        if (pendingReplies.remove(channelId) != null) markChanged()
    }

    fun onSetPendingReplyShouldMention(channelId: Long, shouldMention: Boolean) = dispatcher.schedule {
        pendingReplies[channelId]?.let { pendingReplies[channelId] = it.copy(shouldMention = shouldMention); markChanged() }
    }

    @StoreThread
    fun handleMessageDelete(channelId: Long, messageIds: Collection<Long>) {
        if (pendingReplies[channelId]?.messageReference?.messageId in messageIds && pendingReplies.remove(channelId) != null) markChanged()
    }

    @StoreThread fun handleMessageDelete(messageDelete: ModelMessageDelete) =
        handleMessageDelete(messageDelete.channelId, messageDelete.messageIds.orEmpty())

    @StoreThread fun handlePreLogout() { if (pendingReplies.isNotEmpty()) { pendingReplies.clear(); markChanged() } }
    override fun snapshotData() { snapshot = Collections.unmodifiableMap(HashMap(pendingReplies)) }
}
