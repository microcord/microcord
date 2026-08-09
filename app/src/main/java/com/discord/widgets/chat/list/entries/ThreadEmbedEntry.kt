package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.message.Message

/** Thread preview rendered below the message that started it. */
data class ThreadEmbedEntry(
    val messageId: Long,
    val thread: Channel,
    val threadMessageCount: Int,
    val mostRecentMessage: Message?,
    val roleMentions: Map<Long, GuildRole>?,
    val nickOrUsernames: Map<Long, String>,
    val animateEmojis: Boolean,
    val mostRecentMessageGuildMember: GuildMember?,
    val parentMessage: Message,
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {

    override val highlightMessage: Message get() = parentMessage
    override val type: Int = ChatListEntry.THREAD_EMBED
    override val key: String = "${ChatListEntry.THREAD_EMBED} -- $messageId"
}
