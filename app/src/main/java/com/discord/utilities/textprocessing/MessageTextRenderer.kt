package com.discord.utilities.textprocessing

import android.content.Context
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.span.DraweeSpanStringBuilder

/**
 * Convenience bridge between a [Message] and the markdown pipeline for chat surfaces.
 *
 * It gathers the name caches a [MessageRenderContext] needs from the store graph, runs
 * [DiscordParser], and hands back both the styled text and a plain-text form suitable for
 * accessibility announcements.
 */
object MessageTextRenderer {

    /**
     * @param text styled output ready for [SimpleDraweeSpanTextView.setDraweeSpanStringBuilder]
     * @param plainText the same content with all markup resolved, for content descriptions
     * @param isLinkifyConflicting true when the view must not run `Linkify` over [text]
     */
    data class Result(
        val text: DraweeSpanStringBuilder,
        val plainText: String,
        val isLinkifyConflicting: Boolean = false,
    )

    /**
     * Renders [message] for [view].
     *
     * @param messageState revealed-spoiler state for this message, when the surface tracks it
     * @param parserOptions selects the rule set; chat rows use [DiscordParser.ParserOptions.DEFAULT]
     * @param isEdited appends the `(edited)` marker to the body
     */
    @JvmStatic
    @JvmOverloads
    fun render(
        message: Message,
        view: SimpleDraweeSpanTextView,
        messageState: StoreMessageState.State? = null,
        parserOptions: DiscordParser.ParserOptions = DiscordParser.ParserOptions.DEFAULT,
        isEdited: Boolean = false,
        onUserMentionClicked: ((Long) -> Unit)? = null,
        onChannelMentionClicked: ((Long) -> Unit)? = null,
        onSpoilerClicked: ((SpoilerNode<*>) -> Unit)? = null,
        onEmojiClicked: ((EmojiNode.EmojiIdAndType) -> Unit)? = null,
        onCommandMentionClicked: ((Long, String) -> Unit)? = null,
    ): Result {
        val renderContext = createRenderContext(
            view.context,
            message,
            onUserMentionClicked,
            onChannelMentionClicked,
            onSpoilerClicked,
            onEmojiClicked,
            onCommandMentionClicked,
        )
        val preprocessor = MessagePreprocessor(
            myUserId = renderContext.myId,
            visibleSpoilerNodeIndices = messageState?.visibleSpoilerNodeIndices,
            embeds = message.embeds,
            shouldJumboify = true,
            maxNodes = null,
        )
        val text = DiscordParser.parseChannelMessage(
            view.context,
            message.content,
            renderContext,
            preprocessor,
            parserOptions,
            isEdited,
        )
        return Result(text, toPlainText(text), preprocessor.isLinkifyConflicting)
    }

    /**
     * Builds the render context for [message] from the current store snapshots.
     *
     * Mention display names prefer the guild nickname over the account username, which is what the
     * message body shows in the reference client.
     */
    @JvmStatic
    fun createRenderContext(
        context: Context,
        message: Message,
        onUserMentionClicked: ((Long) -> Unit)? = null,
        onChannelMentionClicked: ((Long) -> Unit)? = null,
        onSpoilerClicked: ((SpoilerNode<*>) -> Unit)? = null,
        onEmojiClicked: ((EmojiNode.EmojiIdAndType) -> Unit)? = null,
        onCommandMentionClicked: ((Long, String) -> Unit)? = null,
    ): MessageRenderContext {
        val guildId = message.guildId
        val members = guildId?.let { StoreStream.getGuilds().getMembers()[it] }.orEmpty()
        val userNames = StoreStream.getUsers().getUsers().mapValuesTo(HashMap()) { it.value.username }
        message.mentions.orEmpty().forEach { user ->
            userNames[user.id] = members[user.id]?.nick?.takeUnless(String::isBlank)
                ?: user.member?.nick?.takeUnless(String::isBlank)
                ?: user.username
        }
        return MessageRenderContext(
            context = context,
            myId = StoreStream.getUsers().getMe().id,
            isAnimationEnabled = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled(),
            userNames = userNames,
            channelNames = StoreStream.getChannels().getChannelNames(),
            roles = guildId?.let { StoreStream.getGuilds().getRoles()[it] }.orEmpty(),
            spoilerOnClick = onSpoilerClicked,
            userMentionOnClick = onUserMentionClicked,
            channelMentionOnClick = onChannelMentionClicked,
            commandMentionOnClick = onCommandMentionClicked,
            emojiClickHandler = onEmojiClicked,
        )
    }

    /** Display text with every marker resolved, used for content descriptions. */
    @JvmStatic
    fun toPlainText(text: CharSequence): String =
        text.toString().replace(ZeroSpaceWidthNode.ZERO_WIDTH_SPACE, "").trim()
}
