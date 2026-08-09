package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
import com.discord.api.application.Application
import com.discord.api.message.activity.MessageActivity

/** Rich-presence game invitation. */
data class GameInviteEntry(
    val authorId: Long,
    val messageId: Long,
    val activity: MessageActivity,
    val application: Application,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.GAME_INVITE
    override val key: String = "${ChatListEntry.GAME_INVITE} -- $messageId"
}
