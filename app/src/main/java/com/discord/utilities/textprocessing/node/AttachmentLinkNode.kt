package com.discord.utilities.textprocessing.node

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan

/**
 * A CDN attachment URL (`cdn.discordapp.com/attachments/...`), rendered as the React Native
 * client does: a paperclip icon followed by the filename, inside a mention-style pill.
 */
class AttachmentLinkNode<T : AttachmentLinkNode.RenderContext>(
    val url: String,
    val filename: String,
) : Node<T>() {

    interface RenderContext : BasicRenderContext, UrlNode.RenderContext

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        val foreground = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_foreground)
        val background = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_background)
        LinkIconSpans.appendLocalIcon(
            builder,
            context,
            R.drawable.ic_attachment_white_24dp,
            foreground,
            background,
        )
        builder.append(filename)
        val spans = listOf<CharacterStyle>(
            ClickableSpan(
                foreground,
                false,
                onLongPress = { renderContext.onLongPressUrl(url) },
            ) { renderContext.onClickUrl(context, url, filename) },
            BackgroundColorSpan(background),
        )
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean =
        other is AttachmentLinkNode<*> && other.url == url && other.filename == filename

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + filename.hashCode()
        return result
    }
}
