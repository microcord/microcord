package com.discord.widgets.chat.list.entries

import com.discord.api.channel.ForumTag
import com.discord.models.member.GuildMember
import com.discord.primitives.ForumTagId

/** Header row for a guild channel, thread, or forum post. */
data class StartOfChatEntry(
    val channelId: Long,
    val channelName: String,
    val canReadMessageHistory: Boolean,
    val canManageChannel: Boolean,
    val canManageThread: Boolean,
    val isThread: Boolean,
    val threadAutoArchiveDuration: Int?,
    val threadCreatorMember: GuildMember?,
    val threadCreatorName: String,
    val isTextInVoice: Boolean,
    val isGuildForumPost: Boolean,
    val availableTags: List<ForumTag>?,
    val appliedTags: List<ForumTagId>?,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.START_OF_CHAT
    override val key: String = "${ChatListEntry.START_OF_CHAT}$channelId"
}
