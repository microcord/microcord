package com.discord.widgets.chat.list.adapter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.api.activity.Activity
import com.discord.api.application.Application
import com.discord.api.botuikit.ComponentType
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreChat
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.io.UserDownloadManager
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.channels.list.WidgetChannelsListItemThreadActions
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.chat.list.actions.MessageActionCoordinator
import com.discord.widgets.chat.list.actions.MessageActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.reactions.WidgetManageReactions
import com.discord.widgets.guild_automod.WidgetReportIssueWithAutoMod
import com.discord.widgets.guild_role_subscriptions.entrypoint.WidgetGuildRoleSubscriptionEntryPoint
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.stickers.StickerSheets
import com.discord.widgets.user.usersheet.WidgetUserSheet
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
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    private val openUrlActions: (String) -> Unit = {},
) : WidgetChatListAdapter.EventHandler {

    private val fragmentManager get() = host.parentFragmentManager

    override fun onBotUiComponentClicked(
        applicationId: Long,
        guildId: Long?,
        channelId: Long,
        messageId: Long,
        messageFlags: Long?,
        componentIndex: Int,
        customId: String?,
        values: List<String>?,
    ) {
        val data = componentInteractionData(customId, values) ?: return
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

    override fun onMessageClicked(message: Message, isThreadStarterMessage: Boolean) {
        if (isThreadStarterMessage) StoreStream.getMessagesLoader().jumpToMessage(message.channelId, message.id)
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
            UserDownloadManager.enqueue(host.requireContext(), uri.toString(), fileName)
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
        RestAPI.api.sendGreetMessage(channelId, RestAPIParams.GreetMessage(listOf(sticker.id)))
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
        StoreStream.getPendingReplies().onCreatePendingReply(channel, message, true, true)
        StoreStream.getMessages().sendMessage(
            channelId = channel.id,
            author = StoreStream.getUsers().getMe(),
            content = "",
            stickers = listOf(resolvedSticker),
            guildId = channel.guildId.takeUnless { it == 0L },
        ).subscribe({}, {})
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

        internal fun componentInteractionData(
            customId: String?,
            values: List<String>?,
        ): RestAPIParams.ComponentInteractionData? {
            customId ?: return null
            return if (values == null) {
                RestAPIParams.ComponentInteractionData.ButtonComponentInteractionData(
                    ComponentType.BUTTON,
                    customId,
                )
            } else {
                RestAPIParams.ComponentInteractionData.SelectComponentInteractionData(
                    ComponentType.SELECT,
                    customId,
                    values,
                )
            }
        }
    }
}
