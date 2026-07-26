package com.discord.widgets.chat.list.model

import com.discord.models.guild.Guild
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry

/**
 * Everything `WidgetChatList` renders: the row list plus the surrounding channel, guild and viewer
 * context the rows themselves do not carry.
 *
 * This is the *live chat* implementation of [WidgetChatListAdapter.Data]. The pinned-messages and
 * recent-mentions surfaces supply their own, which is why the adapter reads
 * [newestKnownMessageId] and [isLoadingMessages] through a cast: only the live surface can page,
 * so only the live surface knows whether there is anything newer to page towards.
 *
 * [isSpoilerClickAllowed] is always true here — tapping a spoiler in the real conversation reveals
 * it. The read-only surfaces override it to false.
 */
data class WidgetChatListModel(
    override val userId: Long,
    override val channelId: Long,
    override val guild: Guild?,
    override val guildId: Long,
    override val channelNames: Map<Long, String>,
    override val oldestMessageId: Long = 0L,
    override val list: List<ChatListEntry>,
    override val myRoleIds: Set<Long>,
    override val newMessagesMarkerMessageId: Long = 0L,
    val newestKnownMessageId: Long = 0L,
    val isLoadingMessages: Boolean,
) : WidgetChatListAdapter.Data {

    override val isSpoilerClickAllowed: Boolean = true
}
