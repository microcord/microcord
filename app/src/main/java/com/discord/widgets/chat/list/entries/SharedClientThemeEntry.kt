package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
import com.discord.utilities.theme.ClientTheme

/** A Nitro custom theme shared in a message (`shared_client_theme`). */
data class SharedClientThemeEntry(
    val theme: ClientTheme.Custom,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.SHARED_CLIENT_THEME
    override val key: String = "${ChatListEntry.SHARED_CLIENT_THEME} -- ${message.id}"
}
