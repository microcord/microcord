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
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan

/**
 * A `discord.com/users/{id}` URL, rendered as an `@username` mention pill (RN parity).
 *
 * The plain [UserMentionNode] cannot carry the source URL, so a long-press could not offer
 * "copy link"; this keeps [url] around for exactly that. Unknown users degrade to the same
 * `@invalid-user` placeholder and stay non-clickable.
 */
class UserLinkNode<T : UserLinkNode.RenderContext>(
    val url: String,
    val userId: Long,
) : Node<T>() {

    interface RenderContext : BasicRenderContext, UrlNode.RenderContext {
        val userNames: Map<Long, String>?
        val userAvatarUrls: Map<Long, String>?
        val userLinkOnClick: ((Long) -> Unit)?
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val name = renderContext.userNames?.get(userId)
        val onClick = renderContext.userLinkOnClick
        if (name == null || onClick == null) {
            UrlNode<UrlNode.RenderContext>(url).render(builder, renderContext)
            return
        }

        val context = renderContext.context
        val start = builder.length
        val foreground = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_foreground)
        val background = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_background)
        renderContext.userAvatarUrls?.get(userId)?.let { avatarUrl ->
            LinkIconSpans.appendAvatarIcon(builder, context, avatarUrl, background)
        }
        builder.append(name)
        val spans = listOf<CharacterStyle>(
            StyleSpan(Typeface.BOLD),
            BackgroundColorSpan(background),
            ClickableSpan(
                foreground,
                false,
                onLongPress = { renderContext.onLongPressUrl(url) },
            ) { onClick(userId) },
        )
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean =
        other is UserLinkNode<*> && other.url == url && other.userId == userId

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + userId.hashCode()
        return result
    }
}
