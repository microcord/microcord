package com.discord.api.forum

import com.discord.api.message.Message

/** The opening message of one forum post, used to render the post preview. */
data class ForumPostFirstMessage(
    val firstMessage: Message?,
)

/** First messages for a batch of forum posts, keyed by thread id. */
data class ForumPostFirstMessages(
    val threads: Map<Long, ForumPostFirstMessage>,
)
