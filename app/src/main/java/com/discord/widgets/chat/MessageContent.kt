package com.discord.widgets.chat

import com.discord.models.user.User

data class MessageContent(
    val textContent: String,
    val mentionedUsers: List<User>,
) {
    override fun toString() = "MessageContent(textContent=<redacted>, mentionedUsers=$mentionedUsers)"
}
