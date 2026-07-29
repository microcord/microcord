package com.discord.widgets.chat.list.utils

import android.content.Context
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.facebook.drawee.span.DraweeSpanStringBuilder

/** Renders the compact, single-line message body used by reply and embedded-message rows. */
object EmbeddedMessageParser {

    fun parse(parserData: ParserData): DraweeSpanStringBuilder {
        val message = parserData.message
        val settings = StoreStream.getUserSettings()
        val embeds = message.embeds.takeIf {
            settings.getIsEmbedMediaInlined() && settings.getIsRenderEmbedsEnabled()
        }
        val preprocessor = MessagePreprocessor(
            message.author?.id ?: 0L,
            parserData.messageState,
            embeds,
            false,
            parserData.maxNodes,
        )
        val adapterData = parserData.adapter.data
        val renderContext = MessageRenderContext(
            context = parserData.context,
            myId = adapterData.userId,
            isAnimationEnabled = parserData.animateEmojis,
            userNames = parserData.nickOrUsernames,
            channelNames = adapterData.channelNames,
            roles = parserData.roleMentions,
            linkColorAttrResId = R.attr.colorTextLink,
            onClickUrl = { context, url, mask -> UriHandler.handleOrUntrusted(context, url, mask) },
            onLongPressUrl = parserData.adapter.eventHandler::onUrlLongClicked,
            spoilerColorRes = ColorCompat.getThemedColor(parserData.context, R.attr.theme_chat_spoiler_bg),
            spoilerRevealedColorRes = ColorCompat.getThemedColor(
                parserData.context,
                R.attr.theme_chat_spoiler_bg_visible,
            ),
            userMentionOnClick = { userId ->
                parserData.adapter.eventHandler.onUserMentionClicked(
                    userId,
                    adapterData.channelId,
                    adapterData.guildId,
                )
            },
            channelMentionOnClick = { channelId ->
                ChannelSelector.getInstance().findAndSet(parserData.context, channelId)
            },
        )
        return DiscordParser.parseChannelMessage(
            parserData.context,
            collapseEmbeddedMessageLines(message.content.orEmpty()),
            renderContext,
            preprocessor,
            DiscordParser.ParserOptions.REPLY,
            false,
        )
    }

    data class ParserData(
        val context: Context,
        val roleMentions: Map<Long, GuildRole>?,
        val nickOrUsernames: Map<Long, String>,
        val animateEmojis: Boolean,
        val messageState: StoreMessageState.State?,
        val maxNodes: Int?,
        val message: Message,
        val adapter: WidgetChatListAdapter,
    )
}

internal fun collapseEmbeddedMessageLines(
    content: String,
    lineSeparator: String = System.lineSeparator(),
): String = Regex(Regex.escape(lineSeparator)).replace(content, " ")
