package com.discord.utilities.textprocessing

import com.discord.models.message.Message
import com.discord.utilities.SnowflakeUtils

/**
 * Whether a message is new enough for the markdown that shipped alongside headers and lists.
 *
 * Enabling them everywhere would restyle old messages that used `#` or `-` as ordinary punctuation,
 * so the reference draws a line at the release and leaves everything before it alone.
 */
object MarkupEligibility {

    private const val IMPROVED_MARKDOWN_EPOCH_MESSAGE_ID = 1088216706570268682L

    private val improvedMarkdownEpochMillis =
        SnowflakeUtils.toTimestamp(IMPROVED_MARKDOWN_EPOCH_MESSAGE_ID)

    /** @param timestampMillis the message's edit time when it has one, else its send time. */
    @JvmStatic
    fun isNewerThanImprovedMarkdownEpoch(timestampMillis: Long): Boolean =
        timestampMillis >= improvedMarkdownEpochMillis

    /** Editing an old message opts it in, which is why the edit time wins when there is one. */
    @JvmStatic
    fun allowsImprovedMarkdown(message: Message): Boolean =
        isNewerThanImprovedMarkdownEpoch(
            (message.editedTimestamp ?: message.timestamp)?.dateTimeMillis ?: 0L,
        )

    /** The rule set a message body gets, for every surface that renders one. */
    @JvmStatic
    fun parserOptionsFor(message: Message): DiscordParser.ParserOptions =
        if (allowsImprovedMarkdown(message)) {
            DiscordParser.ParserOptions.DEFAULT
        } else {
            DiscordParser.ParserOptions.LEGACY_MESSAGE
        }
}
