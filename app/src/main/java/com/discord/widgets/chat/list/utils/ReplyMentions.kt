package com.discord.widgets.chat.list.utils

import com.discord.models.message.Message

/**
 * Reference `modules/replies/didReplyMention`: whether [message] pings the author of the message it
 * replies to, which is what the preview marks with an `@` before their name.
 *
 * The reply's own mention list is the one that answers this — the server adds the referenced author
 * to it when the reply was sent with `mention_author`.
 */
fun didReplyMention(message: Message, repliedMessage: Message?): Boolean {
    val repliedAuthorId = repliedMessage?.author?.id ?: return false
    return message.mentions.orEmpty().any { it.id == repliedAuthorId }
}
