package com.discord.utilities.textprocessing.node

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.StyleSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan

/**
 * A `discord.com/channels/{guild|@me}/{channel}/{message?}` URL, rendered the way the React Native
 * client renders it: a channel-mention pill showing `#channel-name` (or the DM's name) instead of
 * the raw link, that navigates to the channel — and jumps to the message when the URL carries one.
 *
 * If the surface installs no click handler, the raw URL is rendered as an ordinary [UrlNode] so it
 * stays tappable. An uncached channel still renders as a `#deleted-channel` mention so a link that
 * is shown before the gateway has connected does not degrade to a bare URL.
 */
class ChannelLinkNode<T : ChannelLinkNode.RenderContext>(
    val url: String,
    val channelId: Long,
    val guildId: Long?,
    val messageId: Long?,
) : Node<T>() {

    interface RenderContext : BasicRenderContext, UrlNode.RenderContext {
        val channelNames: Map<Long, String>?
        val guildNames: Map<Long, String>?
        val guildIconUrls: Map<Long, String>?
        val viewingGuildId: Long
        val channelLinkOnClick: ((channelId: Long, guildId: Long?, messageId: Long?) -> Unit)?
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val onClick = renderContext.channelLinkOnClick
        if (onClick == null) {
            UrlNode<UrlNode.RenderContext>(url).render(builder, renderContext)
            return
        }
        val name = renderContext.channelNames?.get(channelId) ?: DELETED_CHANNEL

        val context = renderContext.context
        val foreground = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_foreground)
        val background = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_background)

        val start = builder.length

        // A link to a channel in another server is prefixed with the source guild's icon + name,
        // inside the same pill as the channel, separated by a right-pointing angle quote.
        val guild = guildId?.takeIf { it != 0L && it != renderContext.viewingGuildId }
        val guildName = guild?.let { renderContext.guildNames?.get(it) }
        if (guildName != null) {
            renderContext.guildIconUrls?.get(guild)?.let { iconUrl ->
                LinkIconSpans.appendAvatarIcon(builder, context, iconUrl, background, sizeDp = 16)
                builder.append(" ")
            }
            builder.append(guildName.take(32).let { if (guildName.length > 32) "$it\u2026" else it })
            builder.append(" \u203A ")
        }

        LinkIconSpans.appendLocalIcon(
            builder,
            context,
            R.drawable.ic_channel_text_16dp,
            foreground,
            background,
        )
        builder.append(name)
        if (messageId != null) {
            // RN renders a message link as `#channel › ` followed by a trailing chat bubble.
            builder.append(" \u203A ")
            LinkIconSpans.appendLocalIcon(
                builder,
                context,
                R.drawable.ic_chat_message_16dp,
                foreground,
                background,
            )
        }
        val spans = listOf<CharacterStyle>(
            StyleSpan(Typeface.BOLD),
            ClickableSpan(
                foreground,
                false,
                onLongPress = { renderContext.onLongPressUrl(url) },
            ) { onClick(channelId, guildId, messageId) },
            BackgroundColorSpan(background),
        )
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean = other is ChannelLinkNode<*> &&
        other.url == url && other.channelId == channelId && other.guildId == guildId && other.messageId == messageId

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + channelId.hashCode()
        result = 31 * result + (guildId?.hashCode() ?: 0)
        result = 31 * result + (messageId?.hashCode() ?: 0)
        return result
    }

    private companion object {
        const val DELETED_CHANNEL = "deleted-channel"
    }
}
