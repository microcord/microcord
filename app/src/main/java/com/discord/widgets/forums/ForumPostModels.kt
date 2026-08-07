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

internal fun mentionedChannelIds(content: String?): Set<Long> = CHANNEL_MENTION.findAll(content.orEmpty())
    .mapNotNull { it.groupValues[1].toLongOrNull() }
    .toSet()

/** The reference shows the first [limit] tags and counts the rest into an overflow pill. */
internal fun resolvePostTags(
    parent: Channel,
    post: Channel,
    emojis: Map<String, Emoji>,
    limit: Int = MAX_VISIBLE_POST_TAGS,
): PostTagsData {
    val tags = parent.availableTags.orEmpty().associateBy(ForumTag::id)
    val applied = post.appliedTags.orEmpty().mapNotNull { id ->
        tags[id]?.let { tag ->
            val animated = (emojis[tag.emojiName] as? ModelEmojiCustom)?.isAnimated == true
            PostTagData.fromTag(tag, animated)
        }
    }
    return PostTagsData(applied.take(limit), (applied.size - limit).coerceAtLeast(0))
}

internal const val MAX_VISIBLE_POST_TAGS = 2

private val CHANNEL_MENTION = Regex("<#(\\d+)>")

data class PostTagsData(val visible: List<PostTagData> = emptyList(), val overflow: Int = 0)
