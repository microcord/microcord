package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.role.GuildRole
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.User

data class PostData(
    val channel: Channel,
    val owner: User?,
    val myUserId: Long,
    val guildMembers: Map<Long, GuildMember>,
    val guildRoles: Map<Long, GuildRole>,
    val channelNames: Map<Long, String>,
    val firstMessage: Message?,
    val isFirstMessageBlocked: Boolean,
    val mostRecentMessageTimestamp: Long?,
    val messageCount: Int?,
    val tag: PostTagData?,
    val mostCommonReaction: MessageReaction?,
    val isDefaultReaction: Boolean,
    val readState: ForumPostReadState,
    val unreadCount: Int?,
    val typingUsers: List<UserGuildMember>,
    val isMediaPost: Boolean = false,
) {
    val isActivePost: Boolean = channel.threadMetadata?.archived != true
    val isPinned: Boolean = channel.flags?.and(Channel.FLAG_PINNED) == Channel.FLAG_PINNED
    val isMe: Boolean = owner?.id == myUserId
}
