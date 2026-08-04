package com.discord.widgets.chat.list.entries

import com.discord.models.botuikit.MessageComponent
import com.discord.models.message.Message

/** Buttons and select menus attached to a bot message. */
data class BotUiComponentEntry(
    val message: Message,
    val applicationId: Long,
    val guildId: Long?,
    val messageComponents: List<MessageComponent>,
    override val isPendingReply: Boolean = false,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.BOT_UI_COMPONENT
    override val key: String =
        "${ChatListEntry.BOT_UI_COMPONENT} -- ${message.nonce ?: message.id}"
}
