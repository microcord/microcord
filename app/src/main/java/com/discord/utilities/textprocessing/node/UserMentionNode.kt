package com.discord.utilities.textprocessing.node

import android.content.Context
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

/**
 * `<@id>` / `<@!id>` user mention plus the two broadcast mentions `@everyone` and `@here`.
 *
 * A user mention is only clickable when the mentioned user is known to the render context, so an
 * uncached mention degrades to plain highlighted text rather than opening an empty profile.
 */
class UserMentionNode<T : UserMentionNode.RenderContext>(
    val type: Type,
    val userId: Long = UNKNOWN_USER_ID,
) : Node<T>() {

    enum class Type { USER, HERE, EVERYONE }

    interface RenderContext {
        val context: Context
        val myId: Long
        val userNames: Map<Long, String>?
        val userMentionOnClick: ((Long) -> Unit)?
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        if (type == Type.USER) {
            renderUserMention(builder, renderContext)
            return
        }

        val start = builder.length
        builder.append(if (type == Type.HERE) HERE_MENTION else EVERYONE_MENTION)
        listOf(
            StyleSpan(Typeface.BOLD),
            BackgroundColorSpan(themedColor(renderContext.context, R.attr.theme_chat_mention_background)),
            ForegroundColorSpan(themedColor(renderContext.context, R.attr.theme_chat_mention_foreground)),
        ).forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun renderUserMention(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val userNames = renderContext.userNames
        val start = builder.length
        val isKnownUser = userNames?.containsKey(userId) == true
        val displayName = userNames?.get(userId) ?: INVALID_USER

        val spans = mutableListOf<CharacterStyle>(
            StyleSpan(Typeface.BOLD),
            BackgroundColorSpan(themedColor(context, R.attr.theme_chat_mention_background)),
        )
        val onClick = renderContext.userMentionOnClick
        spans += if (isKnownUser && onClick != null) {
            ClickableSpan(themedColor(context, R.attr.theme_chat_mention_foreground), false) { onClick(userId) }
        } else {
            ForegroundColorSpan(themedColor(context, R.attr.theme_chat_mention_foreground))
        }

        builder.append(MENTION_PREFIX).append(displayName)
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean = other is UserMentionNode<*> && other.userId == userId

    override fun hashCode(): Int = userId.hashCode()

    companion object {
        const val UNKNOWN_USER_ID = -1L

        private const val MENTION_PREFIX = "@"
        private const val HERE_MENTION = "@here"
        private const val EVERYONE_MENTION = "@everyone"

        /** Not localized in the reference client either; it is a protocol level placeholder. */
        private const val INVALID_USER = "invalid-user"
    }
}
