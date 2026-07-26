package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.message.embed.MessageEmbed
import com.discord.i18n.I18nSpans
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.AutoModSystemMessageEmbedEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The moderator-facing AutoMod notice: "this message was blocked in #channel by rule X".
 *
 * Unlike an ordinary embed the payload is structured — the blocked text is the embed description and
 * the channel and rule arrive as named embed fields — so the row reads those fields rather than
 * rendering the embed's own layout, and offers the two moderator affordances (open the author's
 * profile actions, report a false positive).
 */
class WidgetChatListAdapterItemAutoModSystemMessageEmbed(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_auto_mod_system_message_embed, adapter) {

    private val avatar: SimpleDraweeView =
        itemView.findViewById(R.id.chat_list_adapter_item_text_avatar)
    private val username: TextView = itemView.findViewById(R.id.username)
    private val messageContent: SimpleDraweeSpanTextView = itemView.findViewById(R.id.message_content)
    private val footerText: TextView = itemView.findViewById(R.id.footer_text)
    private val actionsButton: TextView = itemView.findViewById(R.id.actions_button)
    private val reportButton: TextView = itemView.findViewById(R.id.report_button)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as AutoModSystemMessageEmbedEntry
        val context = adapter.context

        processMessageText(entry)
        configureAuthor(entry)
        configureFooter(entry)

        reportButton.setOnClickListener {
            adapter.eventHandler.onReportIssueWithAutoMod(context, entry.message)
        }
        actionsButton.setOnClickListener {
            adapter.eventHandler.onMessageAuthorLongClicked(entry.message, entry.author?.guildId)
        }
    }

    private fun configureAuthor(entry: AutoModSystemMessageEmbedEntry) {
        val author = entry.message.author
        if (author != null) {
            val coreUser = CoreUser(author)
            IconUtils.setIcon(avatar, coreUser, R.dimen.avatar_size_chat, guildMember = entry.author)
            username.text = GuildMember.getNickOrUsername(entry.author, coreUser)
            username.setTextColor(
                GuildMember.getColor(
                    entry.author,
                    ThemedColor.resolve(adapter.context, R.attr.colorHeaderPrimary),
                ),
            )
        }

        val openAuthorActions = View.OnClickListener {
            adapter.eventHandler.onMessageAuthorLongClicked(entry.message, entry.message.guildId)
        }
        username.setOnClickListener(openAuthorActions)
        avatar.setOnClickListener(openAuthorActions)
    }

    /**
     * "#channel • Rule: name".
     *
     * The channel is named by id in an embed field, so it is resolved against the channel store;
     * an id the viewer can no longer see leaves the name blank rather than printing the raw id.
     *
     * NOTE: the reference draws the channel name on a rounded chip
     * (`utilities/textprocessing/SimpleRoundedBackgroundSpan`). That span has not been reconstructed,
     * so the name renders as a plain tappable run.
     */
    private fun configureFooter(entry: AutoModSystemMessageEmbedEntry) {
        val channelId = entry.embed.fieldValue(FIELD_CHANNEL_ID)?.toLongOrNull()
        val channel = channelId?.let { StoreStream.getChannels().getChannel(it) }
        val channelName = channel?.getDisplayNameOrDefault(footerText.context).orEmpty()
        val ruleName = entry.embed.fieldValue(FIELD_RULE_NAME).orEmpty()

        footerText.text = I18nSpans
            .render(
                footerText.context.getString(R.string.guild_automod_channel_rule_subtext),
                "channelName" to channelName,
                "ruleName" to ruleName,
            )
            .withClickableHook(CHANNEL_HOOK) {
                if (channel != null) StoreStream.selectChannel(channel.guildId, channel.id)
            }
        footerText.setOnClickListener {
            if (channel != null) StoreStream.selectChannel(channel.guildId, channel.id)
        }
    }

    /** The blocked text itself, which AutoMod puts in the embed's description. */
    private fun processMessageText(entry: AutoModSystemMessageEmbedEntry) {
        val context = messageContent.context
        val isEdited = (entry.message.editedTimestamp?.dateTimeMillis ?: 0L) > 0L
        val rendered = DiscordParser.parseChannelMessage(
            context = context,
            messageText = entry.embed.description,
            messageRenderContext = messageRenderContext(context, entry),
            preprocessor = messagePreprocessor(adapter.data.userId, entry.message, entry.messageState),
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

    private fun messageRenderContext(
        context: Context,
        entry: AutoModSystemMessageEmbedEntry,
    ) = MessageRenderContext(
        context = context,
        myId = adapter.data.userId,
        isAnimationEnabled = entry.animateEmojis,
        userNames = entry.nickOrUsernames,
        channelNames = adapter.data.channelNames,
        roles = entry.roles,
        onLongPressUrl = adapter.eventHandler::onUrlLongClicked,
        // The blocked text is quoted evidence; revealing spoilers inside it changes nothing.
        spoilerOnClick = { },
        userMentionOnClick = { userId ->
            adapter.eventHandler.onUserMentionClicked(userId, adapter.data.channelId, adapter.data.guildId)
        },
        channelMentionOnClick = { channelId ->
            StoreStream.selectChannel(adapter.data.guildId, channelId)
        },
    )

    private companion object {
        private const val FIELD_CHANNEL_ID = "channel_id"
        private const val FIELD_RULE_NAME = "rule_name"
        private const val CHANNEL_HOOK = "channelHook"

        private fun MessageEmbed.fieldValue(name: String): String? =
            fields?.firstOrNull { it.name == name }?.value
    }
}
