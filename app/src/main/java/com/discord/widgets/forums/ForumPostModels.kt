package com.discord.widgets.forums

import com.discord.api.channel.Channel
import com.discord.api.channel.ForumTag
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom

internal fun messageCountText(count: Int?): String? = count?.let { if (it < 50) "$it" else "50+" }

internal fun unreadCountText(count: Int?): String? = when {
    count == null || count == 0 -> null
    count < 10 -> count.toString()
    else -> "10+"
}

internal fun resolvePostTag(parent: Channel, post: Channel, emojis: Map<String, Emoji>): PostTagData? {
    val tags = parent.availableTags.orEmpty().associateBy(ForumTag::id)
    return post.appliedTags.orEmpty().firstNotNullOfOrNull { id ->
        tags[id]?.let { tag ->
            val animated = (emojis[tag.emojiName] as? ModelEmojiCustom)?.isAnimated == true
            PostTagData.fromTag(tag, animated)
        }
    }
}
