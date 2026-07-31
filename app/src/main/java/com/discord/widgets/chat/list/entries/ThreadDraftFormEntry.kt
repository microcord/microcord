package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import com.discord.stores.StoreThreadDraft

/** Inline form used while naming a new thread. */
data class ThreadDraftFormEntry(
    val parentChannel: Channel,
    val parentMessageId: Long?,
    val guildId: Long,
    val guild: Guild?,
    val defaultAutoArchiveDuration: Int?,
    val threadDraftState: StoreThreadDraft.ThreadDraftState,
    val canCreatePrivateThread: Boolean,
    val canSeePrivateThreadOption: Boolean,
    val canCreatePublicThread: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.THREAD_DRAFT_FORM
    override val key: String = "${ChatListEntry.THREAD_DRAFT_FORM}${parentChannel.id}$parentMessageId"
}
