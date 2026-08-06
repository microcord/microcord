package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.text.BidiFormatter
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MarkupEligibility
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.spans.SpoilerSpan
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils
import com.discord.utilities.user.UserUtils.displayName
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.AvatarDecorationView
import com.discord.views.FailedUploadList
import com.discord.views.GuildTagView
import com.discord.views.ThemedColor
import com.discord.views.typing.TypingDots
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.ChatAttachmentView
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.utils.didReplyMention
import com.discord.widgets.roles.RoleIconView
import com.facebook.drawee.span.DraweeSpanStringBuilder
import kotlin.math.max

/**
 * A message: the author block, the body, the reply header above it and the failure state below it.
 *
 * The same class drives three layouts — the full row, the compact continuation of a block, and the
 * failed-send row — which is why almost every view is optional. Each layout supplies the views its
 * variant needs and the configuration steps quietly skip the rest, rather than there being three
 * near-identical holders.
 *
 * The body is never set as plain text. It goes through [DiscordParser], which resolves mentions,
 * emoji, spoilers, code blocks and timestamps against a [MessageRenderContext] built from the
 * surrounding channel, so the row also owns the click handling for everything inside the text.
 */
class WidgetChatListAdapterItemMessage(
    layout: Int,
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(layout, adapter) {

    private val itemText: SimpleDraweeSpanTextView =
        itemView.findViewById(R.id.chat_list_adapter_item_text)
    private val itemAttachments: LinearLayout? =
        itemView.findViewById(R.id.chat_list_adapter_item_attachments)
    private val itemAvatar: ImageView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_avatar)
    private val itemAvatarDecoration: AvatarDecorationView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_avatar_decoration)
    private val itemName: TextView? = itemView.findViewById(R.id.chat_list_adapter_item_text_name)
    private val itemRoleIcon: RoleIconView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_role_icon)
    private val itemTag: TextView? = itemView.findViewById(R.id.chat_list_adapter_item_text_tag)
    private val itemGuildTag: GuildTagView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_guild_tag)
    private val replyHolder: View? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_decorator)
    private val replyLinkItem: View? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_decorator_reply_link_icon)
    private val replyLeadingViewsHolder: View? =
        itemView.findViewById(R.id.chat_list_adapter_item_reply_leading_views)
    private val replyName: TextView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_decorator_reply_name)
    private val replyGuildTag: GuildTagView? =
        itemView.findViewById<GuildTagView>(R.id.chat_list_adapter_item_text_reply_guild_tag)?.apply { setSize(10f) }
    private val replyIcon: ImageView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_decorator_reply_icon)
    private val replyAvatar: ImageView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_decorator_avatar)
    private val replyText: SimpleDraweeSpanTextView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_reply_content)
    private val itemTimestamp: TextView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_timestamp)
    private val failedUploadList: FailedUploadList? =
        itemView.findViewById(R.id.chat_list_adapter_item_failed_upload_list)
    private val itemAlertText: TextView? =
        itemView.findViewById(R.id.chat_list_adapter_item_alert_text)
    private val itemLoadingText: TextView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_loading)
    private val backgroundHighlight: View? =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterHighlight: View? =
        itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val loadingDots: TypingDots? = itemView.findViewById(R.id.chat_overlay_typing_dots)
    private val sendError: ImageView? =
        itemView.findViewById(R.id.chat_list_adapter_item_text_error)
    private val threadEmbedSpine: ImageView? =
        itemView.findViewById(R.id.chat_list_adapter_item_thread_embed_spine)
    private val threadStarterMessageHeader: View? =
        itemView.findViewById(R.id.thread_starter_message_header)
    private val communicationDisabledIcon: ImageView? =
        itemView.findViewById(R.id.chat_list_adapter_item_communication_disabled_icon)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as MessageEntry
        // Without a viewer there is no "me" to resolve mentions or permissions against, so the row
        // is left as it was rather than rendered against a half-built model.
        if (adapter.data.userId == 0L) return

        val message = entry.message
        val isThreadStarterMessage = entry.isThreadStarterMessage

        configureItemTag(message, entry.isGuildForumPostAuthor)
        itemGuildTag?.bind(message.author?.primaryGuild)
        if (backgroundHighlight != null && gutterHighlight != null) {
            configureCellHighlight(message, backgroundHighlight, gutterHighlight, entry.isPendingReply)
        }
        configureAuthorName(entry, message)

        itemTimestamp?.text = TimeUtils.toReadableTimeString(
            itemView.context,
            message.timestamp?.dateTimeMillis ?: 0L,
        )

        // An interaction that is still running replaces the body with a status line; otherwise the
        // body is parsed. Whichever view ends up carrying the text is the one accessibility reads.
        configureInteractionMessage(entry)
        val spokenTextView = if (shouldShowInteractionMessage(message) && itemLoadingText != null) {
            itemLoadingText
        } else {
            processMessageText(itemText, entry)
            itemText
        }
        ViewCompat.setAccessibilityDelegate(
            itemView,
            MessageAccessibilityDelegate(spokenTextView, itemName, itemTag, itemTimestamp),
        )

        threadStarterMessageHeader?.visibility =
            if (isThreadStarterMessage) View.VISIBLE else View.GONE

        itemView.setOnClickListener {
            adapter.eventHandler.onMessageClicked(message, isThreadStarterMessage)
        }
        itemView.setOnLongClickListenerConsumeClick {
            // The rendered text ends in a zero-width space the parser appends; the action sheet
            // copies this string, so that character is trimmed back off here.
            val rendered = itemText.text
            adapter.eventHandler.onMessageLongClicked(
                message,
                rendered.subSequence(0, max(rendered.length - 1, 0)),
                isThreadStarterMessage,
            )
        }

        configureAccessories(entry)
        configureReplyPreview(entry)
        configureAvatar(entry, message)
        configureRoleIcon(entry)
        configureFailedUploads(message)
        configureAlertText(message)
        configureThreadSpine(message, isThreadStarterMessage)
        configureCommunicationDisabled(entry.author, entry.permissionsForChannel)
    }

    // region author block

    private fun configureAuthorName(entry: MessageEntry, message: Message) {
        val itemName = itemName ?: return
        itemName.text = entry.authorDisplayName()
        itemName.setTextColor(authorTextColor(entry.author))
        itemName.setOnClickListener {
            adapter.eventHandler.onMessageAuthorNameClicked(message, adapter.data.guildId)
        }
        itemName.setOnLongClickListenerConsumeClick {
            adapter.eventHandler.onMessageAuthorLongClicked(message, adapter.data.guildId)
        }
    }

    /**
     * The badge after the author name: `SYSTEM` for Discord's own notices, `SERVER` for a webhook
     * relaying a followed channel, `OP` for the author of a forum post, `BOT` otherwise. Verified
     * bots additionally get the tick.
     */
    private fun configureItemTag(message: Message, isGuildForumPostAuthor: Boolean) {
        val itemTag = itemTag ?: return
        val author = message.author ?: return
        val user = CoreUser(author)
        val isPublicGuildSystemMessage = isPublicGuildSystemMessage(message)
        val isFollowedChannelPost = message.isFollowedChannelPost()

        val showTag = user.isBot || user.isSystemUser || isPublicGuildSystemMessage ||
            isFollowedChannelPost || isGuildForumPostAuthor
        itemTag.visibility = if (showTag) View.VISIBLE else View.GONE
        itemTag.setText(
            when {
                user.isSystemUser || isPublicGuildSystemMessage -> R.string.system_dm_tag_system
                isFollowedChannelPost -> R.string.bot_tag_server
                isGuildForumPostAuthor -> R.string.bot_tag_forum_original_poster
                else -> R.string.bot_tag_bot
            },
        )
        val verifiedTick = with(UserUtils) { if (user.isVerifiedBot) R.drawable.ic_verified_10dp else 0 }
        itemTag.setCompoundDrawablesWithIntrinsicBounds(verifiedTick, 0, 0, 0)
    }

    /**
     * Reference: `utilities/guilds/PublicGuildUtils.isPublicGuildSystemMessage`. That utility has
     * not been reconstructed yet, so the two ids it keys on are inlined here.
     */
    private fun isPublicGuildSystemMessage(message: Message): Boolean =
        message.messageReference?.guildId == PUBLIC_GUILD_ANNOUNCEMENTS_GUILD_ID ||
            message.author?.id == PUBLIC_GUILD_UPDATES_WEBHOOK_USER_ID

    private fun configureAvatar(entry: MessageEntry, message: Message) {
        val itemAvatar = itemAvatar ?: return
        itemAvatarDecoration?.bind(entry.author?.avatarDecoration ?: message.author?.avatarDecorationData?.value)
        itemAvatar.setOnClickListener {
            adapter.eventHandler.onMessageAuthorAvatarClicked(message, adapter.data.guildId)
        }
        itemAvatar.setOnLongClickListenerConsumeClick {
            adapter.eventHandler.onMessageAuthorLongClicked(message, adapter.data.guildId)
        }

        // Reference `createMessageContent`: only a local interaction placeholder falls back to the
        // application's icon, and only when neither the author nor the member has an avatar. The
        // hash is the application's own — an ordinary message merely carrying `application_id`
        // still renders its author.
        val application = message.application
        if (message.isLocalApplicationCommand() &&
            message.author?.avatar?.value == null &&
            entry.author?.avatarHash == null &&
            application?.icon != null
        ) {
            IconUtils.setIcon(itemAvatar, IconUtils.getApplicationIcon(application.id, application.icon))
            return
        }

        val author = message.author ?: return
        IconUtils.setIcon(
            itemAvatar,
            CoreUser(author),
            R.dimen.avatar_size_chat,
            guildMember = entry.author,
        )
    }

    private fun configureRoleIcon(entry: MessageEntry) {
        val itemRoleIcon = itemRoleIcon ?: return
        // The entry does not carry the guild's roles, so they are resolved from the message's own
        // channel — the pins and search surfaces render messages from more than one guild.
        val guildId = StoreStream.getChannels().getChannel(entry.message.channelId)?.guildId
        val guildRoles = guildId?.let { StoreStream.getGuilds().getRoles()[it] }
        val roles = entry.author?.getRoles().orEmpty()
        itemRoleIcon.setRoleIconPreview(GuildMember.getHighestRoleIconRole(roles, guildRoles))
    }

    /**
     * A member who is timed out is dimmed and marked, but only for moderators — everyone else sees
     * an ordinary message, exactly as the reference does.
     */
    private fun configureCommunicationDisabled(guildMember: GuildMember?, permissionsForChannel: Long?) {
        val isCommunicationDisabled = guildMember?.isCommunicationDisabled() == true
        val canModerate = PermissionUtils.can(Permission.MODERATE_MEMBERS, permissionsForChannel) ||
            PermissionUtils.can(Permission.ADMINISTRATOR, permissionsForChannel)
        val show = isCommunicationDisabled && canModerate
        communicationDisabledIcon?.visibility = if (show) View.VISIBLE else View.GONE
        itemAvatar?.alpha = if (show) DIMMED_ALPHA else 1f
    }

    private fun authorTextColor(member: GuildMember?): Int = GuildMember.getColor(
        member,
        ThemedColor.resolve(itemView.context, R.attr.colorHeaderPrimary),
    )

    // endregion

    // region body

    /**
     * Parses [MessageEntry.message]'s content and hands the result to [textView].
     *
     * Deleted crossposts render the "original message deleted" notice instead of their stale body;
     * webhook messages are allowed masked links, which ordinary users are not; and the first
     * message of a forum post gets the wider forum-post ruleset.
     */
    private fun processMessageText(textView: SimpleDraweeSpanTextView, entry: MessageEntry) {
        val context = textView.context
        val message = entry.message
        val isEdited = (message.editedTimestamp?.dateTimeMillis ?: 0L) > 0L
        val text = if (message.isSourceDeleted()) {
            context.getString(R.string.source_message_deleted)
        } else {
            message.content.orEmpty()
        }

        val preprocessor = messagePreprocessor(adapter.data.userId, message, entry.messageState)
        val renderContext = messageRenderContext(context, entry, spoilerClickHandler(message))
        val parserOptions = if (entry.isGuildForumPostFirstMessage) {
            DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE
        } else {
            MarkupEligibility.parserOptionsFor(message)
        }
        val rendered = DiscordParser.parseChannelMessage(
            context,
            text,
            renderContext,
            preprocessor,
            parserOptions,
            isEdited,
        )

        // The parser already produced link spans; letting Linkify run over the same text would
        // duplicate or corrupt them, so only the patterns the parser does not handle are left on.
        textView.autoLinkMask =
            if (!preprocessor.isLinkifyConflicting && shouldLinkify(message.content)) {
                Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS
            } else {
                0
            }
        // The parser always appends a zero-width space, so `rendered` is never empty; an embed-only
        // message would keep a blank line between the author and the embed.
        textView.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        textView.setDraweeSpanStringBuilder(rendered)
        // A message that has not reached the server yet, or that slow mode rejected, is dimmed.
        textView.alpha =
            if (message.type == TYPE_LOCAL || message.type == TYPE_LOCAL_SLOWMODE) DIMMED_ALPHA else 1f
    }

    /**
     * `Linkify` walks the text with a set of regexes whose cost grows with the number of candidate
     * separators, so a long message dense with dots (a stack trace, a list of file names) is left
     * un-linkified rather than freezing the row while it scans.
     */
    private fun shouldLinkify(content: String?): Boolean {
        if (content == null) return false
        if (content.length < LINKIFY_MAX_SAFE_LENGTH) return true
        var dots = 0
        for (character in content) {
            if (character == '.') {
                dots++
                if (dots >= LINKIFY_MAX_DOTS) return false
            }
        }
        return true
    }

    private fun messagePreprocessor(
        userId: Long,
        message: Message,
        messageState: StoreMessageState.State?,
    ): MessagePreprocessor {
        val settings = StoreStream.getUserSettings()
        // Embeds are only handed to the preprocessor so it can strip a link whose sole purpose was
        // to produce the embed rendered right underneath; if embeds are off there is nothing to
        // strip and the link must stay.
        val embeds = if (settings.getIsEmbedMediaInlined() && settings.getIsRenderEmbedsEnabled()) {
            message.embeds
        } else {
            null
        }
        return MessagePreprocessor(userId, messageState, embeds, true, null)
    }

    private fun messageRenderContext(
        context: Context,
        entry: MessageEntry,
        spoilerClickHandler: ((SpoilerNode<*>) -> Unit)?,
    ) = MessageRenderContext(
        context = context,
        myId = adapter.data.userId,
        isAnimationEnabled = entry.animateEmojis,
        userNames = entry.nickOrUsernames,
        channelNames = adapter.data.channelNames,
        roles = entry.roles,
        linkColorAttrResId = R.attr.colorTextLink,
        onClickUrl = { clickContext, url, mask -> UriHandler.handleOrUntrusted(clickContext, url, mask) },
        onLongPressUrl = { url -> adapter.eventHandler.onUrlLongClicked(url) },
        spoilerColorRes = ThemedColor.resolve(context, R.attr.theme_chat_spoiler_bg),
        spoilerRevealedColorRes = ThemedColor.resolve(context, R.attr.theme_chat_spoiler_bg_visible),
        spoilerOnClick = spoilerClickHandler,
        userMentionOnClick = { userId ->
            adapter.eventHandler.onUserMentionClicked(
                userId,
                adapter.data.channelId,
                adapter.data.guildId,
            )
        },
        channelMentionOnClick = ::selectChannel,
    )

    private fun selectChannel(channelId: Long) {
        ChannelSelector.getInstance().findAndSet(itemView.context, channelId)
    }

    /**
     * Revealing a spoiler is a per-message, per-node piece of state so it survives scrolling. Read-
     * only surfaces (pins, search) pass `isSpoilerClickAllowed = false` and get no handler at all.
     */
    private fun spoilerClickHandler(message: Message): ((SpoilerNode<*>) -> Unit)? {
        if (!adapter.data.isSpoilerClickAllowed) return null
        return { spoilerNode ->
            StoreStream.getMessageState().revealSpoiler(message.id, spoilerNode.id)
            itemView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        }
    }

    // endregion

    // region interaction (slash-command) status line

    private fun shouldShowInteractionMessage(message: Message): Boolean =
        message.isLocalApplicationCommand() || message.isLoading()

    /**
     * While a slash command is in flight the body is replaced by "…is thinking" plus animated dots;
     * once it fails the dots stop and an error icon appears next to the reason.
     */
    private fun configureInteractionMessage(entry: MessageEntry) {
        val message = entry.message
        val show = shouldShowInteractionMessage(message)

        loadingDots?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) loadingDots?.start() else loadingDots?.stop()
        itemLoadingText?.visibility = if (show) View.VISIBLE else View.GONE
        itemText.visibility = if (show) View.GONE else View.VISIBLE
        sendError?.visibility = View.GONE
        if (!show) return

        // The BOT badge would sit next to a name that is not the one doing the talking.
        itemTag?.visibility = View.GONE

        itemLoadingText?.setTextColor(
            if (message.isFailed()) {
                ThemedColor.resolve(itemView.context, R.attr.colorError)
            } else {
                ThemedColor.resolve(itemView.context, R.attr.colorTextMuted)
            },
        )

        when {
            message.isLocalApplicationCommand() && message.isLoading() -> {
                itemLoadingText?.text = I18nHooks.format(
                    itemText.context,
                    R.string.application_command_waiting,
                    mapOf("applicationName" to entry.authorDisplayName()),
                )
            }
            message.isLocalApplicationCommand() && message.isFailed() -> {
                itemLoadingText?.setText(R.string.application_command_failed)
                sendError?.visibility = View.VISIBLE
                loadingDots?.visibility = View.INVISIBLE
                loadingDots?.stop()
            }
            message.isLocalApplicationCommand() -> {
                itemLoadingText?.setText(R.string.application_command_sending)
            }
        }
    }

    // endregion

    // region reply header

    /**
     * The one-line preview above a reply.
     *
     * An interaction response shows which command produced it instead; an ordinary reply shows the
     * referenced message, or a placeholder when that message is blocked, deleted, or not loaded.
     */
    /**
     * Attachments, embeds and bot components are drawn inside the message's own row so that
     * spacing, the press highlight and the long-press menu treat the message as one unit. Each is
     * rendered by its normal view holder, so their binding logic is shared with the standalone rows
     * the forwarded and search surfaces still use.
     */
    private fun configureAccessories(entry: MessageEntry) {
        val container = itemAttachments ?: return
        container.visibility = if (entry.accessories.isEmpty()) View.GONE else View.VISIBLE
        while (container.childCount > entry.accessories.size) {
            val index = container.childCount - 1
            (container.getChildAt(index).getTag(R.id.chat_list_adapter_item_attachments) as? FragmentLifecycleListener)
                ?.onPause()
            container.removeViewAt(index)
        }
        entry.accessories.forEachIndexed { index, accessory ->
            val existing = container.getChildAt(index)
            val holder = existing
                ?.getTag(R.id.chat_list_adapter_item_attachments)
                ?.takeIf { it is MGRecyclerViewHolder<*, *> && existing.getTag(R.id.chat_list_adapter_item_text) == accessory.type }
                ?.let {
                    @Suppress("UNCHECKED_CAST")
                    it as MGRecyclerViewHolder<WidgetChatListAdapter, ChatListEntry>
                }
                ?: adapter.onCreateViewHolder(container, accessory.type).also { created ->
                    created.itemView.setTag(R.id.chat_list_adapter_item_attachments, created)
                    created.itemView.setTag(R.id.chat_list_adapter_item_text, accessory.type)
                    existing?.let { container.removeViewAt(index) }
                    container.addView(created.itemView, index)
                }
            holder.bind(index, accessory)
            adoptAccessoryTouches(holder.itemView, entry)
        }
    }

    /**
     * A hosted row arrives wired for standalone use: its own press background and its own
     * click/long-click handlers. Both are stripped so the message row owns the highlight and the
     * action sheet, and long-press is forwarded from any child that would otherwise swallow it.
     */
    private fun adoptAccessoryTouches(view: View, entry: MessageEntry) {
        view.background = null
        view.setOnClickListener(null)
        view.isClickable = false
        val forward = View.OnLongClickListener {
            val rendered = itemText.text
            adapter.eventHandler.onMessageLongClicked(
                entry.message,
                rendered.subSequence(0, max(rendered.length - 1, 0)),
                entry.isThreadStarterMessage,
            )
            true
        }
        view.isLongClickable = false
        // Only views that already swallow touches get the forwarder: making anything else
        // long-clickable would consume the gesture and stop the message row being pressed at all.
        fun apply(target: View) {
            if (target !== view && target.isClickable) target.setOnLongClickListener(forward)
            if (target is ViewGroup) repeat(target.childCount) { apply(target.getChildAt(it)) }
        }
        apply(view)
    }

    private fun configureReplyPreview(entry: MessageEntry) {
        val replyHolder = replyHolder ?: return
        val replyLinkItem = replyLinkItem ?: return
        replyGuildTag?.bind(null)

        val replyData = entry.replyData
        val isInteraction = entry.message.isInteraction()
        if (!isInteraction && (replyData == null || entry.message.type != TYPE_REPLY)) {
            replyHolder.visibility = View.GONE
            replyLinkItem.visibility = View.GONE
            return
        }
        replyHolder.visibility = View.VISIBLE
        replyLinkItem.visibility = View.VISIBLE

        if (isInteraction) {
            configureReplyInteraction(entry)
            return
        }
        if (replyData == null) return

        val repliedEntry = replyData.messageEntry
        when {
            replyData.isRepliedUserBlocked ->
                configureReplySystemMessage(R.string.reply_quote_message_blocked)
            replyData.messageState is StoreMessageReplies.MessageState.Unloaded ->
                configureReplySystemMessage(R.string.reply_quote_message_not_loaded)
            replyData.messageState is StoreMessageReplies.MessageState.Deleted ->
                configureReplySystemMessage(R.string.reply_quote_message_deleted)
            replyData.messageState is StoreMessageReplies.MessageState.Loaded && repliedEntry != null ->
                configureLoadedReply(entry, repliedEntry)
        }
    }

    private fun configureLoadedReply(entry: MessageEntry, repliedEntry: MessageEntry) {
        val repliedMessage = repliedEntry.message
        replyHolder?.setOnClickListener {
            StoreStream.getMessagesLoader()
                .jumpToMessage(repliedMessage.channelId, repliedMessage.id)
        }
        when (repliedMessage.type) {
            TYPE_GUILD_MEMBER_JOIN -> {
                configureReplySystemMessageUserJoin(repliedEntry)
                return
            }
            TYPE_ROLE_SUBSCRIPTION_PURCHASE -> {
                configureReplySystemMessageRoleSubscriptionPurchase(repliedEntry)
                return
            }
        }

        val author = repliedMessage.author ?: return
        replyGuildTag?.bind(author.primaryGuild)
        configureReplyAuthor(
            CoreUser(author),
            repliedEntry.author,
            repliedEntry,
            didReplyMention(entry.message, repliedMessage),
        )

        val replyText = replyText ?: return
        if (replyLeadingViewsHolder == null) return

        val content = repliedMessage.content.orEmpty()
        if (content.isNotEmpty()) {
            val rendered = parseReplyPreview(replyText.context, repliedEntry)
            rendered.setSpan(leadingEdgeSpan(), 0, rendered.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            replyText.setDraweeSpanStringBuilder(rendered)
            configureReplyLayoutDirection()
            return
        }

        when {
            repliedMessage.hasStickers() ->
                configureReplyContentWithResourceId(R.string.reply_quote_sticker_mobile)
            repliedMessage.hasAttachments() ||
                repliedMessage.shouldShowReplyPreviewAsAttachment() ||
                repliedMessage.hasEmbeds() ->
                configureReplyContentWithResourceId(R.string.reply_quote_no_text_content_mobile)
            else -> Log.e(TAG, "Unhandled reply preview: $repliedEntry")
        }
    }

    /**
     * Reference: `list/utils/EmbeddedMessageParser`, not yet reconstructed.
     *
     * Two things differ from the main body: the AST is capped at [MAX_REPLY_AST_NODES] so a very
     * long message cannot blow up the one-line preview, and newlines collapse to spaces so a
     * multi-line message still reads as one line.
     */
    private fun parseReplyPreview(context: Context, repliedEntry: MessageEntry): DraweeSpanStringBuilder {
        val repliedMessage = repliedEntry.message
        val authorId = repliedMessage.author?.id ?: 0L
        val settings = StoreStream.getUserSettings()
        val embeds = if (settings.getIsEmbedMediaInlined() && settings.getIsRenderEmbedsEnabled()) {
            repliedMessage.embeds
        } else {
            null
        }
        val preprocessor = MessagePreprocessor(
            authorId,
            StoreMessageState.State(),
            embeds,
            false,
            MAX_REPLY_AST_NODES,
        )
        val renderContext = MessageRenderContext(
            context = context,
            myId = adapter.data.userId,
            isAnimationEnabled = repliedEntry.animateEmojis,
            userNames = repliedEntry.nickOrUsernames,
            channelNames = adapter.data.channelNames,
            roles = repliedEntry.roles,
            linkColorAttrResId = R.attr.colorTextLink,
            onClickUrl = { clickContext, url, mask -> UriHandler.handleOrUntrusted(clickContext, url, mask) },
            onLongPressUrl = { url -> adapter.eventHandler.onUrlLongClicked(url) },
            spoilerColorRes = ThemedColor.resolve(context, R.attr.theme_chat_spoiler_bg),
            spoilerRevealedColorRes = ThemedColor.resolve(context, R.attr.theme_chat_spoiler_bg_visible),
            spoilerOnClick = null,
            userMentionOnClick = { userId ->
                adapter.eventHandler.onUserMentionClicked(
                    userId,
                    adapter.data.channelId,
                    adapter.data.guildId,
                )
            },
            channelMentionOnClick = ::selectChannel,
        )
        val singleLine = repliedMessage.content.orEmpty().replace(LINE_SEPARATOR, " ")
        return DiscordParser.parseChannelMessage(
            context,
            singleLine,
            renderContext,
            preprocessor,
            DiscordParser.ParserOptions.REPLY,
            false,
        )
    }

    private fun configureReplyInteraction(entry: MessageEntry) {
        val message = entry.message
        val interaction = message.interaction ?: return
        val interactionUser = interaction.user ?: return
        val user = CoreUser(interactionUser)
        replyGuildTag?.bind(interactionUser.primaryGuild)

        configureReplyAvatar(user, entry.author)
        configureReplyAuthor(user, entry.interactionAuthor, entry)
        replyName?.setOnClickListener {
            adapter.eventHandler.onMessageAuthorAvatarClicked(message, adapter.data.guildId)
        }

        val replyText = replyText ?: return
        replyText.movementMethod = LinkMovementMethod.getInstance()
        val commandSpan = ClickableSpan(
            ThemedColor.resolve(replyText.context, R.attr.colorTextLink),
            underline = false,
        ) { onCommandClicked(message, interaction.id, interactionUser.id) }
        val text = I18nHooks.format(
            replyText.context,
            R.string.system_message_application_command_reply,
            mapOf("commandName" to interaction.name),
            mapOf(
                "commandNameOnClick" to Hook(
                    spans = listOf(StyleSpan(Typeface.BOLD), commandSpan),
                ),
            ),
        )
        configureReplyText(SpannableString.valueOf(text))
    }

    /**
     * A command that has not reached the server yet has nothing to open, and a context-menu command
     * has no invocation screen at all — both are inert.
     */
    private fun onCommandClicked(message: Message, interactionId: Long?, interactionUserId: Long) {
        if (interactionId == null) return
        if (message.isLocalApplicationCommand() && !message.isFailed()) return
        if (message.type == TYPE_CONTEXT_MENU_COMMAND) return
        val authorId = message.author?.id ?: return
        adapter.eventHandler.onCommandClicked(
            interactionId,
            adapter.data.guild?.id,
            message.channelId,
            message.id,
            interactionUserId,
            authorId,
            message.nonce,
        )
    }

    private fun configureReplyAuthor(
        replyAuthor: User,
        replyGuildMember: GuildMember?,
        messageEntry: MessageEntry,
        didMention: Boolean = false,
    ) {
        configureReplyAvatar(replyAuthor, replyGuildMember ?: messageEntry.author)
        val name = messageEntry.nickOrUsernames[replyAuthor.id] ?: replyAuthor.displayName
        configureReplyName(name, authorTextColor(replyGuildMember), didMention)
    }

    private fun configureReplyAvatar(messageAuthor: User?, guildMember: GuildMember?) {
        val replyIcon = replyIcon ?: return
        val replyAvatar = replyAvatar ?: return
        if (messageAuthor == null) {
            replyIcon.visibility = View.VISIBLE
            replyAvatar.visibility = View.GONE
            return
        }
        replyIcon.visibility = View.GONE
        replyAvatar.visibility = View.VISIBLE
        IconUtils.setIcon(
            replyAvatar,
            messageAuthor,
            R.dimen.avatar_size_reply,
            guildMember = guildMember,
        )
    }

    private fun configureReplyName(originalAuthorName: String?, color: Int, didMention: Boolean) {
        val replyName = replyName ?: return
        if (originalAuthorName.isNullOrEmpty()) {
            replyName.visibility = View.GONE
            return
        }
        replyName.visibility = View.VISIBLE
        // The "@" marks a reply that also pings the person being replied to.
        replyName.text = if (didMention) "@$originalAuthorName" else originalAuthorName
        replyName.setTextColor(color)
    }

    private fun configureReplySystemMessage(@StringRes stringResourceId: Int) {
        configureReplyAvatar(null, null)
        configureReplyName("", 0, false)
        configureReplyContentWithResourceId(stringResourceId)
    }

    private fun configureReplySystemMessageUserJoin(repliedEntry: MessageEntry) {
        val replyIcon = replyIcon ?: return
        val replyAvatar = replyAvatar ?: return
        val replyText = replyText ?: return
        replyIcon.visibility = View.GONE
        replyAvatar.visibility = View.VISIBLE
        replyAvatar.setImageResource(R.drawable.ic_group_join)
        configureReplyName("", 0, false)

        val context = replyText.context
        val text = I18nHooks.format(
            context,
            MessageUtils.getSystemMessageUserJoin(context, repliedEntry.message.id),
            mapOf("username" to repliedEntry.authorDisplayName()),
        )
        configureReplyText(SpannableString(text.toString()), REPLY_SYSTEM_MESSAGE_ALPHA)
    }

    private fun configureReplySystemMessageRoleSubscriptionPurchase(repliedEntry: MessageEntry) {
        val replyText = replyText ?: return
        configureReplyAvatar(null, null)
        configureReplyName("", 0, false)
        val context = replyText.context
        val guildName = StoreStream.getGuilds().getGuild(adapter.data.guildId)?.name
        val text = MessageUtils.getSystemMessageRoleSubscriptionPurchase(
            context,
            guildName,
            repliedEntry.authorDisplayName(),
            repliedEntry.message.roleSubscriptionData,
            // The reply preview binds no hooks, but it still needs the hook-aware renderer: the
            // default one does not strip the `$[](hookName)` form, and this text is flattened to a
            // plain string immediately below.
            render = { ctx, res, args -> I18nHooks.format(ctx, res, args) },
        )
        configureReplyText(SpannableString(text.toString()), REPLY_SYSTEM_MESSAGE_ALPHA)
    }

    private fun configureReplyContentWithResourceId(@StringRes stringResourceId: Int) {
        val replyText = replyText ?: return
        val content = SpannableString(replyText.context.resources.getString(stringResourceId))
        content.setSpan(
            StyleSpan(Typeface.ITALIC),
            0,
            content.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        configureReplyText(content, REPLY_SYSTEM_MESSAGE_ALPHA)
    }

    private fun configureReplyText(content: Spannable, alpha: Float = 1f) {
        val replyText = replyText ?: return
        if (replyLeadingViewsHolder == null) return
        content.setSpan(leadingEdgeSpan(), 0, content.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        replyText.alpha = alpha
        replyText.text = content
        configureReplyLayoutDirection()
    }

    /**
     * The reply avatar and name are drawn in a separate holder that overlaps the text view, so the
     * first line of the preview has to be indented past them by hand.
     */
    private fun leadingEdgeSpan(): LeadingMarginSpan {
        val holder = replyLeadingViewsHolder ?: return LeadingMarginSpan.Standard(0, 0)
        holder.measure(0, 0)
        return LeadingMarginSpan.Standard(holder.measuredWidth, 0)
    }

    /** An RTL preview has to lay its avatar out on the right, whatever the surrounding locale. */
    private fun configureReplyLayoutDirection() {
        val replyHolder = replyHolder ?: return
        val replyText = replyText ?: return
        replyHolder.layoutDirection = if (BidiFormatter.getInstance().isRtl(replyText.text)) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    // endregion

    // region failure state

    private fun configureFailedUploads(message: Message) {
        val failedUploadList = failedUploadList ?: return
        val localAttachments = message.localAttachments
        if (message.isFailed() && !localAttachments.isNullOrEmpty()) {
            failedUploadList.visibility = View.VISIBLE
            failedUploadList.setUp(localAttachments)
        } else {
            failedUploadList.visibility = View.GONE
        }
    }

    private fun configureAlertText(message: Message) {
        val itemAlertText = itemAlertText ?: return
        if (!message.isFailed()) {
            itemAlertText.visibility = View.GONE
            return
        }
        itemAlertText.visibility = View.VISIBLE
        itemAlertText.setText(
            if (message.type == TYPE_LOCAL_INVALID) {
                R.string.invalid_attachments_failure
            } else {
                R.string.send_message_failure
            },
        )
    }

    private fun configureThreadSpine(message: Message, isThreadStarterMessage: Boolean) {
        val threadEmbedSpine = threadEmbedSpine ?: return
        val show = message.hasThread() && !isThreadStarterMessage
        threadEmbedSpine.visibility = if (show) View.VISIBLE else View.GONE
    }

    // endregion

    /**
     * Reads a message as one node instead of four.
     *
     * The author, badge and timestamp are folded into the body's description and then hidden from
     * the accessibility tree, so a screen reader announces "Alice, BOT, 3:42 PM" followed by the
     * message rather than stopping at each view. Unrevealed spoilers are replaced with a label so
     * their content is not read out.
     *
     * Reference: `widgets/chat/list/ChatListItemMessageAccessibilityDelegate`, which is owned by
     * another wave; kept private here until that class lands.
     */
    private class MessageAccessibilityDelegate(
        private val textView: TextView,
        private val nameView: TextView?,
        private val tagView: TextView?,
        private val timestampView: TextView?,
    ) : AccessibilityDelegateCompat() {

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val description = SpannableStringBuilder(textView.text)
            description.getSpans(0, description.length, SpoilerSpan::class.java).forEach { span ->
                if (!span.isRevealed) {
                    description.replace(
                        description.getSpanStart(span),
                        description.getSpanEnd(span),
                        host.context.getString(R.string.spoiler_hidden_a11y_label),
                    )
                }
            }

            val header = listOfNotNull(
                nameView?.text,
                tagView?.text?.takeIf { tagView.visibility == View.VISIBLE },
                timestampView?.text,
            )
            if (header.isNotEmpty()) {
                description.insert(0, header.joinToString(", ") + "\n")
            }

            nameView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            tagView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            timestampView?.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

            info.text = description
            // The row's own click opens the action sheet, which is exposed as a long-press action
            // instead; announcing it as "double tap to activate" would be misleading.
            info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            info.movementGranularities = MOVEMENT_GRANULARITIES_ALL
        }

        private companion object {
            /** character | word | line | paragraph. */
            private const val MOVEMENT_GRANULARITIES_ALL = 15
        }
    }

    private companion object {
        private const val TAG = "ChatListAdapterMessage"

        private const val TYPE_DEFAULT = 0
        private const val TYPE_GUILD_MEMBER_JOIN = 7
        private const val TYPE_REPLY = 19
        private const val TYPE_CONTEXT_MENU_COMMAND = 23
        private const val TYPE_ROLE_SUBSCRIPTION_PURCHASE = 25
        private const val TYPE_LOCAL = -1
        private const val TYPE_LOCAL_INVALID = -3
        private const val TYPE_LOCAL_SLOWMODE = -6

        private const val DIMMED_ALPHA = 0.5f
        private const val REPLY_SYSTEM_MESSAGE_ALPHA = 0.64f

        /** A reply preview is one line; anything past this many nodes could never be seen. */
        private const val MAX_REPLY_AST_NODES = 50

        private const val LINKIFY_MAX_SAFE_LENGTH = 200
        private const val LINKIFY_MAX_DOTS = 50

        private val LINE_SEPARATOR: Regex =
            Regex(System.getProperty("line.separator") ?: "\n")

        /** Reference: `PublicGuildUtils`. */
        private const val PUBLIC_GUILD_ANNOUNCEMENTS_GUILD_ID = 667560445975986187L
        private const val PUBLIC_GUILD_UPDATES_WEBHOOK_USER_ID = 669627189624307712L
    }
}

/**
 * The name this message's author renders as — the guild nickname where there is one, otherwise the
 * account username. The mapping is resolved once, when the row's entry is built, so every row that
 * mentions the same person agrees on what to call them.
 */
internal fun MessageEntry.authorDisplayName(): String? =
    message.author?.id?.let(nickOrUsernames::get)

internal fun Message.isFollowedChannelPost(): Boolean =
    type == 0 && messageReference != null && messageReference.type != FORWARD_REFERENCE_TYPE

private const val FORWARD_REFERENCE_TYPE = 1
