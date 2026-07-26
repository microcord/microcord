package com.discord.utilities.textprocessing.node

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.themedColor

/**
 * `<@&id>` role mention.
 *
 * A role with an explicit colour tints the mention with that colour; a default coloured role uses
 * the theme mention colours instead.
 */
class RoleMentionNode<T : RoleMentionNode.RenderContext>(
    val roleId: Long,
) : Node<T>() {

    interface RenderContext {
        val context: Context
        val roles: Map<Long, GuildRole>?
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val start = builder.length
        val role = renderContext.roles?.get(roleId)
        if (role == null) {
            builder.append(DELETED_ROLE)
            return
        }

        val hasCustomColor = role.color != DEFAULT_ROLE_COLOR
        val foreground = if (hasCustomColor) {
            ColorUtils.setAlphaComponent(role.color, OPAQUE_ALPHA)
        } else {
            themedColor(renderContext.context, R.attr.theme_chat_mention_foreground)
        }
        val background = if (hasCustomColor) {
            ColorUtils.setAlphaComponent(role.color, MENTION_BACKGROUND_ALPHA)
        } else {
            themedColor(renderContext.context, R.attr.theme_chat_mention_background)
        }

        builder.append(MENTION_PREFIX).append(role.name)
        listOf(
            StyleSpan(Typeface.BOLD),
            ForegroundColorSpan(foreground),
            BackgroundColorSpan(background),
        ).forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean = other is RoleMentionNode<*> && other.roleId == roleId

    override fun hashCode(): Int = roleId.hashCode()

    private companion object {
        const val MENTION_PREFIX = "@"

        /** Not localized in the reference client either; it is a protocol level placeholder. */
        const val DELETED_ROLE = "deleted-role"
        const val DEFAULT_ROLE_COLOR = 0
        const val OPAQUE_ALPHA = 255
        const val MENTION_BACKGROUND_ALPHA = 25
    }
}
