package com.discord.utilities.textprocessing.node

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.discord.R
import com.discord.simpleast.core.node.Node
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.ClickableSpan
import com.discord.widgets.guilds.onboarding.WidgetChannelsAndRoles
import com.discord.utilities.textprocessing.spans.TextBackgroundSpan

/**
 * `<id:route>` and `<id:route:itemId>`: a mention of one of the guild's built-in pages, as the RN
 * client renders `staticRouteLink`. Linked Roles names the role after a `›` when it is known.
 */
class StaticRouteNode<T : StaticRouteNode.RenderContext>(
    val route: String,
    val itemId: Long?,
) : Node<T>() {

    interface RenderContext : RoleMentionNode.RenderContext {
        val viewingGuildId: Long
    }

    override fun render(builder: SpannableStringBuilder, renderContext: T) {
        val context = renderContext.context
        val start = builder.length
        val foreground = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_foreground)
        val background = ColorCompat.getThemedColor(context, R.attr.theme_chat_mention_background)
        LinkIconSpans.appendLocalIcon(
            builder,
            context,
            when (route) {
                "browse", "customize" -> R.drawable.ic_static_route_browse
                "linked-roles" -> R.drawable.ic_static_route_link
                else -> R.drawable.ic_static_route_signpost
            },
            foreground,
            background,
        )
        builder.append(
            context.getString(
                when (route) {
                    "browse" -> R.string.channel_browser_title
                    "customize" -> R.string.channels_and_roles
                    "linked-roles" -> R.string.static_route_linked_roles
                    else -> R.string.static_route_server_guide
                },
            ),
        )
        if (route == "linked-roles") itemId?.let { renderContext.roles?.get(it) }?.let { builder.append(" › ").append(it.name) }

        val guildId = renderContext.viewingGuildId
        val spans = listOf(
            StyleSpan(Typeface.BOLD),
            TextBackgroundSpan(background),
            if ((route == "customize" || route == "browse") && guildId != 0L) {
                ClickableSpan(foreground, false) { WidgetChannelsAndRoles.launch(context, guildId, browse = route == "browse") }
            } else {
                ForegroundColorSpan(foreground)
            },
        )
        spans.forEach { builder.setSpan(it, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    override fun equals(other: Any?): Boolean = other is StaticRouteNode<*> && other.route == route && other.itemId == itemId

    override fun hashCode(): Int = route.hashCode() * 31 + itemId.hashCode()
}
