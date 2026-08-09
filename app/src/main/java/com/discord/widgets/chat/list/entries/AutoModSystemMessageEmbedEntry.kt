package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState

/** Embed attached to an AutoMod action system message. */
data class AutoModSystemMessageEmbedEntry(
    val channel: Channel,
    val message: Message,
    val messageState: StoreMessageState.State?,
    val embed: MessageEmbed,
    val author: GuildMember?,
    val animateEmojis: Boolean,
    val roles: Map<Long, GuildRole>?,
    val nickOrUsernames: Map<Long, String>,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.AUTOMOD_SYSTEM_MESSAGE
    override val key: String = "${ChatListEntry.AUTOMOD_SYSTEM_MESSAGE}${message.id}"
}
