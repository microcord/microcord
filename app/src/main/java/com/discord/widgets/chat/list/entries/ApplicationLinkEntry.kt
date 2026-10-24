package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

/** An app install link (`oauth2/authorize` without bot or other server scopes) embedded in a message. */
data class ApplicationLinkEntry(
    val applicationId: Long,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.APPLICATION_LINK
    override val key: String = "${ChatListEntry.APPLICATION_LINK} -- ${message.id} -- $applicationId"
}
