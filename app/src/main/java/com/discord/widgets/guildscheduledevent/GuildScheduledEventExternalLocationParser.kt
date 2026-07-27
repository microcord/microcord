package com.discord.widgets.guildscheduledevent

import android.content.Context
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.simple.SimpleMarkdownRules
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.Rules

/**
 * Renders the free-text location of an external event.
 *
 * Only two rules: URLs become tappable links, everything else stays literal. An external location
 * is user-supplied text, so full message markdown is deliberately *not* applied — a venue called
 * `**Main Hall**` should read as written.
 *
 * The last rendered location is cached because the card re-binds on every store emission and
 * re-parsing produces a fresh set of spans, which makes the text flicker.
 */
class GuildScheduledEventExternalLocationParser {

    private data class Cache(val location: String, val output: CharSequence)

    private var cache = Cache("", "")

    fun getTextFromLocation(
        context: Context,
        locationInfo: GuildScheduledEventLocationInfo,
    ): CharSequence = when (locationInfo) {
        is GuildScheduledEventLocationInfo.ChannelLocation -> locationInfo.locationName
        is GuildScheduledEventLocationInfo.ExternalLocation -> parse(context, locationInfo.locationName)
    }

    private fun parse(context: Context, location: String): CharSequence {
        if (cache.location == location) return cache.output
        val rendered = AstRenderer.render(
            PARSER.parse(location, MessageParseState.initialState),
            MessageRenderContext(context = context, myId = 0L, isAnimationEnabled = false),
        )
        cache = Cache(location, rendered)
        return rendered
    }

    private companion object {
        private val PARSER: Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> =
            Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>(false).apply {
                addRule(Rules.createUrlRule())
                addRule(SimpleMarkdownRules.createTextRule())
            }
    }
}
