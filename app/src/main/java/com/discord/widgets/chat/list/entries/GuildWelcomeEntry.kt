package com.discord.widgets.chat.list.entries

/** Setup actions shown at the start of a new guild's default channel. */
data class GuildWelcomeEntry(
    val isOwner: Boolean,
    val guildHasIcon: Boolean,
    val canInvite: Boolean,
    val guildId: Long,
    val guildName: String,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GUILD_WELCOME
    override val key: String = "${ChatListEntry.GUILD_WELCOME}$guildId"
}
