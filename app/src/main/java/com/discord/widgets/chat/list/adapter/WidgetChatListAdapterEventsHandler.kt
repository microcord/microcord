package com.discord.widgets.chat.list.adapter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextSwitcher
import android.widget.TextView
import android.widget.Toast
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.api.activity.Activity
import com.discord.api.application.Application
import com.discord.api.botuikit.ComponentType
import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.databinding.ViewQuickMessageActionsBinding
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreChat
import com.discord.stores.StoreStream
import com.discord.utilities.attachments.AttachmentUrlRefresher
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.io.UserDownloadManager
import com.discord.utilities.messagesend.MessageSendLocation
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.channels.list.WidgetChannelsListItemThreadActions
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.chat.input.sticker.StickerMessageSender
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.actions.MessageActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.reactions.WidgetManageReactions
import com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheet
import com.discord.widgets.guild_automod.WidgetReportIssueWithAutoMod
import com.discord.widgets.guild_automod.WidgetAutoModIncidentActions
import com.discord.widgets.guild_automod.WidgetAutoModUserActions
import com.discord.widgets.guild_role_subscriptions.entrypoint.WidgetGuildRoleSubscriptionEntryPoint
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.stickers.StickerSheets
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.views.ReactionView
import java.lang.ref.WeakReference

/** Routes every live-chat row event to its 126.21 store or navigation owner. */
class WidgetChatListAdapterEventsHandler(
    private val host: AppFragment,
    private val flexInputViewModel: AppFlexInputViewModel,
    private val messageActionCoordinator: MessageActionCoordinator,
    private val openPins: (Long) -> Unit,
    private val openCall: (Long, WidgetChatListAdapterItemCallMessage.CallStatus) -> Unit,
    private val openApplicationCommand: (
        interactionId: Long,
        guildId: Long?,
        channelId: Long,
        messageId: Long,
        interactionUserId: Long,
        applicationId: Long,
        messageNonce: String?,
    ) -> Unit = { interactionId, guildId, channelId, messageId, userId, applicationId, nonce ->
        WidgetApplicationCommandBottomSheet.show(
            host.parentFragmentManager,
            interactionId,
            messageId,
            channelId,
            guildId,
            userId,
            applicationId,
            nonce,
        )
    },
    private val openUrlActions: (String) -> Unit,
) : WidgetChatListAdapter.EventHandler {

    private val fragmentManager get() = host.parentFragmentManager
    private val tapHandler = Handler(Looper.getMainLooper())
    private val stickerMessageSender = StickerMessageSender()
    private var pendingTap: PendingTap? = null
    private var quickActionsPopup: PopupWindow? = null
    private var quickActionsMessageId = 0L

    override fun onBotUiComponentClicked(
        applicationId: Long,
        guildId: Long?,
        channelId: Long,
        messageId: Long,
        messageFlags: Long?,
        componentIndex: Int,
        customId: String?,
        values: List<String>?,
        componentType: ComponentType,
    ) {
        val data = componentInteractionData(customId, values, componentType) ?: return
        StoreStream.getInteractions().sendComponentInteraction(
            applicationId,
            guildId,
            channelId,
            messageId,
            componentIndex,
            data,
            messageFlags,
        )
    }

    override fun onCallMessageClicked(
        voiceChannelId: Long,
        callStatus: WidgetChatListAdapterItemCallMessage.CallStatus,
    ) = openCall(voiceChannelId, callStatus)

    override fun onCommandClicked(
        interactionId: Long,
        guildId: Long?,
        channelId: Long,
        messageId: Long,
        interactionUserId: Long,
        applicationId: Long,
        messageNonce: String?,
    ) = openApplicationCommand(
        interactionId,
        guildId,
        channelId,
        messageId,
        interactionUserId,
        applicationId,
        messageNonce,
    )

    override fun onDismissClicked(message: Message) = StoreStream.getMessages().deleteMessage(message)

    override fun onDismissLocalMessageClicked(message: Message) {
        StoreStream.getMessages().deleteMessage(message)
    }

    override fun onInteractionStateUpdated(interactionState: StoreChat.InteractionState) =
        StoreStream.getChat().setInteractionState(interactionState)

    override fun onListClicked() {
        dismissQuickActions()
        host.hideKeyboard()
        flexInputViewModel.hideExpressionTray()
    }

    override fun onMessageAuthorAvatarClicked(message: Message, guildId: Long) {
        if (message.isCrosspost()) {
            showCrosspostGuild(message)
            return
        }
        message.author?.id?.let {
            WidgetUserSheet.show(fragmentManager, it, message.channelId, guildId)
        }
    }

    override fun onMessageAuthorLongClicked(message: Message, guildId: Long?) {
        if (message.isWebhook()) {
            Toast.makeText(host.requireContext(), R.string.user_profile_failure_to_open_message, Toast.LENGTH_SHORT).show()
            return
        }
        message.author?.id?.let {
            WidgetUserSheet.show(fragmentManager, it, message.channelId, guildId)
        }
    }

    override fun onMessageAuthorNameClicked(message: Message, guildId: Long) {
        if (message.isCrosspost()) showCrosspostGuild(message)
        else StoreStream.getChat().appendMention(message.author?.let(::CoreUser), guildId)
    }

    override fun onMessageBlockedGroupClicked(message: Message) =
        StoreStream.getChat().toggleBlockedMessageGroup(message.id)

    override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean, anchor: View?) {
        val tapTap = FeatureFlags.tapTapEnabled() && TapTapBehavior.canHandle(message)
        val quickActions = FeatureFlags.quickMessageActionsEnabled() && anchor != null &&
            QuickMessageActionsBehavior.canShow(
                message,
                isThreadStarterMessage,
                StoreStream.getChannels().getChannel(message.channelId)?.isArchivedThread() == true,
            )
        if (!tapTap && !quickActions) {
            handleSingleTap(message, isThreadStarterMessage)
            return
        }

        val tapWindow = if (quickActions) QUICK_ACTION_DELAY_MS else TapTapBehavior.TIMEOUT_MS
        val now = SystemClock.uptimeMillis()
        val previous = pendingTap
        if (previous?.messageId == message.id && now - previous.startedAt <= tapWindow) {
            tapHandler.removeCallbacks(previous.callback)
            pendingTap = null
            if (tapTap) handleDoubleTap(message)
            return
        }

        if (previous != null) {
            tapHandler.removeCallbacks(previous.callback)
            previous.callback.run()
        }
        lateinit var callback: Runnable
        callback = Runnable {
            if (pendingTap?.callback === callback) {
                pendingTap = null
                if (quickActions) showQuickMessageActions(requireNotNull(anchor), message)
                else handleSingleTap(message, isThreadStarterMessage)
            }
        }
        pendingTap = PendingTap(message.id, now, callback)
        tapHandler.postDelayed(callback, tapWindow)
    }

    override fun onMessageLongClicked(
        message: Message,
        formattedMessage: CharSequence,
        isThreadStarterMessage: Boolean,
    ) {
        if (!isThreadStarterMessage) showMessageActions(message)
    }

    override fun onOpenPinsClicked(message: Message) = openPins(message.channelId)

    override fun onQuickAddReactionClicked(
        guildId: Long,
        myUserId: Long,
        channelId: Long,
        messageId: Long,
    ) {
        val message = StoreStream.getMessages().getMessage(channelId, messageId) ?: return
        WidgetEmojiPickerSheet.show(fragmentManager) { emoji ->
            messageActionCoordinator.toggleReaction(message, emoji)
        }
    }

    override fun onQuickDownloadClicked(uri: Uri, fileName: String): Boolean {
        host.requestMediaDownload {
            val url = uri.toString()
            // An old row still holds the link it was sent with, and the CDN refuses one whose
            // signature has lapsed, so it is re-signed before the download is handed off.
            AttachmentUrlRefresher.refreshExpired(listOf(url), System.currentTimeMillis())
                .onErrorReturn { mapOf(url to url) }
                .ui()
                .appSubscribe(errorClass = WidgetChatListAdapterEventsHandler::class.java) { refreshed ->
                    UserDownloadManager.enqueue(host.requireContext(), refreshed[url] ?: url, fileName)
                }
        }
        return true
    }

    override fun onReactionClicked(
        guildId: Long,
        myUserId: Long,
        channelId: Long,
        messageId: Long,
        reaction: MessageReaction,
        canAddReactions: Boolean,
    ) {
        if (!canAddReactions) {
            Toast.makeText(
                host.requireContext(),
                R.string.archived_thread_reactions_disabled_toast,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        StoreStream.getMessages().getMessage(channelId, messageId)?.let {
            messageActionCoordinator.toggleReaction(it, reaction.emoji)
        }
    }

    override fun onReactionLongClicked(
        guildId: Long,
        channelId: Long,
        messageId: Long,
        reaction: MessageReaction,
    ) = WidgetManageReactions.create(channelId, messageId, host.requireContext(), reaction)

    override fun onReportIssueWithAutoMod(context: Context, message: Message) =
        WidgetReportIssueWithAutoMod.launch(context, message.channelId, message.id)

    override fun onAutoModIncidentActions(guildId: Long, messageId: Long) =
        WidgetAutoModIncidentActions.show(fragmentManager, guildId, messageId)

    override fun onAutoModUserActions(message: Message, guildId: Long) {
        val author = message.author ?: return
        WidgetAutoModUserActions.show(
            fragmentManager,
            guildId,
            message.channelId,
            author.id,
            author.globalName?.takeIf(String::isNotBlank) ?: author.username,
        )
    }

    override fun onRoleSubscriptionPurchaseTierClick(
        roleSubscriptionListingId: Long,
        guildId: Long,
        userId: Long,
        channelId: Long,
        messageId: Long,
    ) = WidgetGuildRoleSubscriptionEntryPoint.launch(
        host.requireContext(),
        guildId,
        ROLE_SUBSCRIPTION_SOURCE,
    )

    override fun onSendGreetMessageClicked(channelId: Long, channelType: Int, sticker: Sticker) {
        RestAPI.api.sendGreetMessage(
            channelId,
            RestAPIParams.GreetMessage(listOf(sticker.id)),
            contextPropertiesOf("location" to MessageSendLocation.GREET),
        )
            .restSubscribeOn(false)
            .ui(host)
            .appSubscribe(WidgetChatListAdapterEventsHandler::class.java) { }
    }

    override fun onShareButtonClick(
        guildEvent: GuildScheduledEvent,
        weakContext: WeakReference<Context>,
        weakFragment: WeakReference<AppFragment>,
    ) {
        val fragment = weakFragment.get() ?: return
        val channel = guildEvent.channelId?.let(StoreStream.getChannels()::getChannel)
        val canShare = GuildScheduledEventUtilities.canShareEvent(guildEvent.channelId, guildEvent.guildId)
        GuildScheduledEventUtilities.launchInvite(canShare, fragment, guildEvent.guildId, channel, guildEvent.id)
    }

    override fun onStickerClicked(message: Message, sticker: BaseSticker) {
        flexInputViewModel.hideKeyboard()
        StickerSheets.show(fragmentManager, host, sticker, message.channelId)
    }

    override fun onSystemMessageCtaClicked(message: Message, channel: Channel, sticker: BaseSticker) {
        val resolvedSticker = StoreStream.getStickers().getStickers()[sticker.getStickerId()] ?: return
        stickerMessageSender.sendReply(channel, resolvedSticker, message)
    }

    override fun onThreadClicked(channel: Channel) =
        ChannelSelector.getInstance().selectChannel(channel, channel.parentId, SelectedChannelAnalyticsLocation.EMBED)

    override fun onThreadLongClicked(channel: Channel) =
        WidgetChannelsListItemThreadActions.show(fragmentManager, channel.id)

    override fun onUrlLongClicked(url: String) = openUrlActions(url)

    override fun onUserActivityAction(
        authorId: Long,
        channelId: Long,
        messageId: Long,
        messageActivityType: MessageActivityType,
        activity: Activity,
        application: Application,
    ) {
        val applicationId = activity.applicationId ?: return
        val sessionId = activity.sessionId ?: return
        if (messageActivityType != MessageActivityType.JOIN) return
        RestAPI.api.userActivityAction(
            authorId,
            applicationId,
            sessionId,
            messageActivityType.apiInt,
            channelId,
            messageId,
        ).restSubscribeOn(false).appSubscribe(WidgetChatListAdapterEventsHandler::class.java) { confirmation ->
            val intent = Intent(
                SDK_ACTION,
                Uri.parse(application.launchUri).buildUpon()
                    .appendPath("join")
                    .appendQueryParameter("application_id", application.id.toString())
                    .appendQueryParameter("secret", confirmation.secret)
                    .build(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                host.requireContext().startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    host.requireContext(),
                    FormatUtils.format(
                        host.getString(R.string.user_activity_not_detected),
                        "name" to application.name,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                application.googlePlayPackageNames.firstOrNull()?.let {
                    UriHandler.directToPlayStore(host.requireContext(), it)
                }
            }
        }
    }

    override fun onUserMentionClicked(userId: Long, channelId: Long, guildId: Long) =
        WidgetUserSheet.show(fragmentManager, userId, channelId, guildId)

    private fun showCrosspostGuild(message: Message) {
        val reference = message.messageReference ?: return
        val guildId = reference.guildId ?: return
        WidgetGuildProfileSheet.show(fragmentManager, false, guildId, reference.channelId)
    }

    private fun handleSingleTap(message: Message, isThreadStarterMessage: Boolean) {
        if (isThreadStarterMessage) StoreStream.getMessagesLoader().jumpToMessage(message.channelId, message.id)
    }

    private fun handleDoubleTap(message: Message) {
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return
        when (TapTapBehavior.action(message, StoreStream.getUsers().getMe().id)) {
            TapTapBehavior.Action.EDIT -> editMessage(message, channel)
            TapTapBehavior.Action.REPLY -> replyToMessage(message, channel)
        }
    }

    private fun editMessage(message: Message, channel: Channel) {
        val actionsHost = host as? WidgetChatListActions.Host ?: return
        WidgetChatListActions.prepareMessageForEdit(
            message,
            channel,
            StoreStream.getGuilds().getGuild(channel.guildId),
        ).ui(host).appSubscribe(WidgetChatListAdapterEventsHandler::class.java) { editable ->
            actionsHost.requestMessageEdit(editable, messageActionCoordinator)
        }
    }

    private fun replyToMessage(message: Message, channel: Channel) = messageActionCoordinator.reply(
        channel,
        message,
        StoreStream.getUsers().getMe().id,
    )

    private fun showQuickMessageActions(anchor: View, message: Message) {
        if (quickActionsPopup?.isShowing == true && quickActionsMessageId == message.id) {
            dismissQuickActions()
            return
        }
        dismissQuickActions()
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return
        val binding = ViewQuickMessageActionsBinding.inflate(LayoutInflater.from(anchor.context))
        val ownMessage = message.author?.id == StoreStream.getUsers().getMe().id
        binding.quickMessageReply.setOnClickListener {
            replyToMessage(message, channel)
            dismissQuickActions()
        }
        binding.quickMessageEdit.visibility = if (ownMessage) View.VISIBLE else View.GONE
        binding.quickMessageDelete.visibility = if (ownMessage) View.VISIBLE else View.GONE
        binding.quickMessageEdit.setOnClickListener {
            editMessage(message, channel)
            dismissQuickActions()
        }
        binding.quickMessageDelete.setOnClickListener {
            messageActionCoordinator.delete(message)
            dismissQuickActions()
        }
        quickActionsPopup = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                quickActionsPopup = null
                quickActionsMessageId = 0L
            }
            showAsDropDown(
                anchor.findViewById<View>(R.id.chat_list_adapter_item_text) ?: anchor,
                dp(anchor, 16),
                dp(anchor, 4),
            )
        }
        quickActionsMessageId = message.id
        StoreStream.getEmojis()
            .getEmojiSet(channel.guildId, channel.id, forReaction = true)
            .take(1)
            .ui(host)
            .appSubscribe(WidgetChatListAdapterEventsHandler::class.java) { emojiSet ->
                if (quickActionsMessageId != message.id) return@appSubscribe
                emojiSet.recentEmojis.orEmpty()
                    .asSequence()
                    .filter { it.isUsable() && it.isAvailable() }
                    .take(QUICK_REACTION_COUNT)
                    .forEach { emoji -> addQuickReaction(binding.quickMessageReactions, message, emoji) }
            }
    }

    private fun addQuickReaction(container: LinearLayout, message: Message, emoji: Emoji) {
        val reactionEmoji = when (emoji) {
            is ModelEmojiCustom -> MessageReactionEmoji(emoji.idStr, emoji.name, emoji.isAnimated)
            else -> MessageReactionEmoji(null, emoji.getMessageContentReplacement(), false)
        }
        val reaction = message.reactions.orEmpty().firstOrNull { existing ->
            if (reactionEmoji.id != null) existing.emoji.id == reactionEmoji.id
            else existing.emoji.id == null && existing.emoji.name == reactionEmoji.name
        } ?: MessageReaction(0, reactionEmoji, false)
        container.addView(
            ReactionView(container.context).apply {
                configure(reaction, message.id, true)
                findViewById<TextSwitcher>(R.id.counter_text_switcher).visibility = View.GONE
                findViewById<TextView>(R.id.emoji_text_view).apply {
                    gravity = Gravity.CENTER
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                        marginEnd = 0
                        gravity = Gravity.CENTER
                    }
                }
                setPadding(dp(this, 6), paddingTop, dp(this, 6), paddingBottom)
                gravity = Gravity.CENTER
                minimumWidth = dp(this, 30)
                setBackgroundResource(R.drawable.drawable_quick_reaction_bg)
                setOnClickListener {
                    StoreStream.getEmojis().onEmojiUsed(emoji, isReaction = true)
                    messageActionCoordinator.toggleReaction(message, reactionEmoji)
                    dismissQuickActions()
                }
            },
            LinearLayout.LayoutParams(dp(container, 30), dp(container, 26)).apply {
                marginStart = dp(container, 4)
            },
        )
    }

    private fun dismissQuickActions() {
        quickActionsPopup?.dismiss()
        quickActionsPopup = null
        quickActionsMessageId = 0L
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()

    private fun showMessageActions(message: Message) {
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return
        WidgetChatListActions.show(
            fragmentManager,
            message.channelId,
            message.id,
            MessageActions.Input(
                authorId = message.author?.id,
                currentUserId = StoreStream.getUsers().getMe().id,
                messageType = message.type,
                isLocal = message.isLocal(),
                canResend = message.canResend(),
                hasContent = !message.content.isNullOrEmpty(),
                permissions = StoreStream.getPermissions().getPermissionsByChannel()[message.channelId],
                pinned = message.pinned == true,
                channelType = channel.type,
            ),
        )
    }

    companion object {
        private const val ROLE_SUBSCRIPTION_SOURCE = "Role Subscriptions Tab"
        private const val SDK_ACTION = "com.discord.intent.action.SDK"
        private const val QUICK_ACTION_DELAY_MS = 300L
        private const val QUICK_REACTION_COUNT = 3

        internal fun componentInteractionData(
            customId: String?,
            values: List<String>?,
            componentType: ComponentType,
        ): RestAPIParams.ComponentInteractionData? {
            customId ?: return null
            return if (values == null) {
                RestAPIParams.ComponentInteractionData.ButtonComponentInteractionData(
                    ComponentType.BUTTON,
                    customId,
                )
            } else {
                RestAPIParams.ComponentInteractionData.SelectComponentInteractionData(
                    componentType,
                    customId,
                    values,
                )
            }
        }
    }

    private data class PendingTap(
        val messageId: Long,
        val startedAt: Long,
        val callback: Runnable,
    )
}

internal object TapTapBehavior {
    const val TIMEOUT_MS = 200L

    enum class Action { REPLY, EDIT }

    fun canHandle(message: Message) = !message.isEphemeralMessage() && !message.isLocal() &&
        !message.isFailed() && !message.isLoading()

    fun action(message: Message, currentUserId: Long) =
        if (message.author?.id == currentUserId) Action.EDIT else Action.REPLY
}

internal object QuickMessageActionsBehavior {
    fun canShow(message: Message, isThreadStarterMessage: Boolean, isArchivedThread: Boolean) =
        !isThreadStarterMessage && !isArchivedThread && !message.isLocal() &&
            !message.isFailed() && !message.isEphemeralMessage()
}
