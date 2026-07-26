package com.discord.models.domain.emoji

/**
 * Everything the emoji picker needs for one snapshot of the user's emoji world.
 *
 * [emojiIndex] maps every name (already disambiguated across guilds) to its emoji so autocomplete
 * and message rendering can resolve `:name:` without scanning the category lists.
 */
open class EmojiSet(
    @JvmField val unicodeEmojis: Map<EmojiCategory, List<Emoji>>,
    @JvmField val customEmojis: Map<Long, List<Emoji>>,
    @JvmField val emojiIndex: Map<String, Emoji>,
    @JvmField val recentEmojis: List<Emoji>,
    @JvmField val favoriteEmoji: Set<Emoji>,
) {
    override fun equals(other: Any?): Boolean = other is EmojiSet && other.canEqual(this) &&
        unicodeEmojis == other.unicodeEmojis && customEmojis == other.customEmojis &&
        emojiIndex == other.emojiIndex && recentEmojis == other.recentEmojis &&
        favoriteEmoji == other.favoriteEmoji

    override fun hashCode(): Int = listOf(
        unicodeEmojis, customEmojis, emojiIndex, recentEmojis, favoriteEmoji,
    ).hashCode()

    override fun toString(): String = "EmojiSet(unicodeEmojis=$unicodeEmojis, " +
        "customEmojis=$customEmojis, emojiIndex=$emojiIndex, recentEmojis=$recentEmojis, " +
        "favoriteEmoji=$favoriteEmoji)"

    protected open fun canEqual(other: Any?): Boolean = other is EmojiSet
}
