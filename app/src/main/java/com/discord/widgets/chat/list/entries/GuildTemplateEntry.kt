package com.discord.widgets.chat.list.entries

/** A guild-template link embedded in a message. */
data class GuildTemplateEntry(
    val userId: Long,
    val messageId: Long,
    val guildTemplateCode: String,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GUILD_TEMPLATE
    override val key: String = "${ChatListEntry.GUILD_TEMPLATE} -- $messageId -- $guildTemplateCode"
}
