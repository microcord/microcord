package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
/** A guild-template link embedded in a message. */
data class GuildTemplateEntry(
    val userId: Long,
    val messageId: Long,
    val guildTemplateCode: String,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.GUILD_TEMPLATE
    override val key: String = "${ChatListEntry.GUILD_TEMPLATE} -- $messageId -- $guildTemplateCode"
}
