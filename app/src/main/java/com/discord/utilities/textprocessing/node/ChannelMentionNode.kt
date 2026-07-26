package com.discord.utilities.textprocessing.node

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.themedColor

/** `<#id>` channel link. Falls back to `#deleted-channel` when the channel is not cached. */
class ChannelMentionNode<T : ChannelMentionNode.RenderContext>(
    val channelId: Long,
) : Node<T>() {

    interface RenderContext : BasicRenderContext {
        val channelNames: Map<Long, String>?
        val channelMentionOnClick: ((Long) -> Unit)?
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        val channelName = renderContext.channelNames?.get(channelId) ?: DELETED_CHANNEL

        val spans = mutableListOf<CharacterStyle>(StyleSpan(Typeface.BOLD))
        val onClick = renderContext.channelMentionOnClick
        spans += if (onClick != null) {
            ClickableSpan(
                themedColor(context, R.attr.theme_chat_mention_foreground),
                false,
            ) { onClick(channelId) }
        } else {
            ForegroundColorSpan(themedColor(context, R.attr.theme_chat_mention_foreground))
        }
        spans += BackgroundColorSpan(themedColor(context, R.attr.theme_chat_mention_background))

        builder.append(CHANNEL_MENTION_PREFIX).append(channelName)
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean = other is ChannelMentionNode<*> && other.channelId == channelId

    override fun hashCode(): Int = channelId.hashCode()

    private companion object {
        const val CHANNEL_MENTION_PREFIX = "#"
        const val DELETED_CHANNEL = "deleted-channel"
    }
}
