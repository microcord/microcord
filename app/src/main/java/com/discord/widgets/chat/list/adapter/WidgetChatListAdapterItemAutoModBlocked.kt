package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.i18n.I18nSpans
import com.discord.i18n.withClickableHook
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The local notice shown to an author whose own message AutoMod refused to send.
 *
 * Only the author sees it and it never reached the server, so the row renders the message they typed
 * exactly as it would have appeared — avatar, name in their role colour, timestamp, parsed body —
 * with a warning and a "dismiss" link that drops the local message from the channel.
 */
class WidgetChatListAdapterItemAutoModBlocked(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_auto_mod_blocked, adapter) {

    private val avatar: SimpleDraweeView =
        itemView.findViewById(R.id.chat_list_adapter_item_text_avatar)
    private val username: TextView = itemView.findViewById(R.id.username)
    private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
    private val messageContent: SimpleDraweeSpanTextView = itemView.findViewById(R.id.message_content)
    private val dismissMessage: LinkifiedTextView = itemView.findViewById(R.id.dismiss_message)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as MessageEntry
        val context = adapter.context

        processMessageText(entry)

        entry.message.author?.let { author ->
            IconUtils.setIcon(
                avatar,
                CoreUser(author),
                R.dimen.avatar_size_chat,
                guildMember = entry.author,
            )
        }

        username.text = entry.author?.nick?.takeUnless(String::isBlank)
            ?: entry.message.author?.globalName?.takeUnless(String::isBlank)
            ?: entry.message.author?.username
        username.setTextColor(
            GuildMember.getColor(entry.author, ThemedColor.resolve(context, R.attr.colorHeaderPrimary)),
        )
        timestamp.text = TimeUtils.toReadableTimeString(
            context,
            entry.message.timestamp?.dateTimeMillis ?: 0L,
            adapter.clock,
        )

        // "Only you can see this • [Dismiss message](onClick)" — the bracketed run is the action.
        dismissMessage.text = I18nSpans
            .render(context.getString(R.string.guild_automod_visibility_message))
            .withClickableHook(DISMISS_HOOK) {
                adapter.eventHandler.onDismissLocalMessageClicked(entry.message)
            }
    }

    /**
     * Renders the blocked body.
     *
     * Embeds are only handed to the preprocessor when the account renders them, because the
     * preprocessor uses them to decide whether the body is nothing but the link that produced an
     * embed — and with embeds off that link must stay visible.
     */
    private fun processMessageText(entry: MessageEntry) {
        val context = messageContent.context
        val message = entry.message
        val isEdited = (message.editedTimestamp?.dateTimeMillis ?: 0L) > 0L
        val rendered = DiscordParser.parseChannelMessage(
            context = context,
            messageText = message.content,
            messageRenderContext = messageRenderContext(context, entry),
            preprocessor = messagePreprocessor(adapter.data.userId, message, entry.messageState),
            parserOptions = DiscordParser.ParserOptions.DEFAULT,
            isEdited = isEdited,
        )
        messageContent.visibility = if (rendered.isNotEmpty()) View.VISIBLE else View.GONE
        messageContent.setDraweeSpanStringBuilder(rendered)
    }

    private fun messagePreprocessor(
        userId: Long,
        message: Message,
        messageState: StoreMessageState.State?,
    ): MessagePreprocessor {
        val userSettings = StoreStream.getUserSettings()
        val embeds = message.embeds
            .takeIf { userSettings.getIsEmbedMediaInlined() && userSettings.getIsRenderEmbedsEnabled() }
        return MessagePreprocessor(userId, messageState, embeds, true, null)
    }

    private fun messageRenderContext(context: Context, entry: MessageEntry) = MessageRenderContext(
        context = context,
        myId = adapter.data.userId,
        isAnimationEnabled = entry.animateEmojis,
        userNames = entry.nickOrUsernames,
        channelNames = adapter.data.channelNames,
        roles = entry.roles,
        onLongPressUrl = adapter.eventHandler::onUrlLongClicked,
        // The message never reached the server, so its spoilers have nowhere to record a reveal.
        spoilerOnClick = { },
        userMentionOnClick = { userId ->
            adapter.eventHandler.onUserMentionClicked(userId, adapter.data.channelId, adapter.data.guildId)
        },
        channelMentionOnClick = { channelId ->
            StoreStream.selectChannel(adapter.data.guildId, channelId)
        },
    )

    private companion object {
        private const val DISMISS_HOOK = "onClick"
    }
}
