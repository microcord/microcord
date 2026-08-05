package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.botuikit.MessageComponent
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState

/** Buttons and select menus attached to a bot message. */
data class BotUiComponentEntry(
    val message: Message,
    val applicationId: Long,
    val guildId: Long?,
    val messageComponents: List<MessageComponent>,
    override val isPendingReply: Boolean = false,
    val messageState: StoreMessageState.State? = null,
    val channel: Channel? = null,
    val guildMembers: Map<Long, GuildMember> = emptyMap(),
    val guildRoles: Map<Long, GuildRole> = emptyMap(),
) : ChatListEntry() {
    override val type: Int = ChatListEntry.BOT_UI_COMPONENT
    override val key: String =
        "${ChatListEntry.BOT_UI_COMPONENT} -- ${message.nonce ?: message.id}"
}
