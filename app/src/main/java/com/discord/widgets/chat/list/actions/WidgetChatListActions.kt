package com.discord.widgets.chat.list.actions

import com.discord.api.message.MessageTypes
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.developer.DeveloperSettings
import com.discord.api.commands.ApplicationCommandIndexCommand
import com.discord.utilities.theme.ThemeRuntime
import com.discord.widgets.chat.input.applauncher.WidgetContextCommandsSheet
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.permissions.ManageMessageContext
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.discriminatorWithPadding
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.widgets.chat.managereactions.WidgetManageReactions
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.themes.ThemeInstaller
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.share.ForwardingPolicy
import com.discord.widgets.share.WidgetIncomingShare
import rx.Observable
import rx.subscriptions.CompositeSubscription
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.plugins.PluginContextMenuHost
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuContext

class WidgetChatListActions : AppBottomSheet() {
    interface Host {
        fun messageActionCoordinator(): MessageActionCoordinator
        fun messageForAction(channelId: Long, messageId: Long): Message?
        fun channelForAction(channelId: Long): Channel?
        fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator)
        fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator)
        fun onMessageActionError(error: Throwable) = Unit
    }

    private var _binding: WidgetChatListActionsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var emojiAdapter: WidgetChatListActionsEmojisAdapter? = null
    private var itemDecorator: RecyclerView.ItemDecoration? = null
    private var channelId = 0L
    private var messageId = 0L

    override fun getContentViewResId() = R.layout.widget_chat_list_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetChatListActionsBinding.bind(view)
        channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID)
        messageId = argumentsOrDefault.getLong(ARG_MESSAGE_ID)
        val recycler = binding.dialogChatActionsAddReactionEmojisList
        emojiAdapter = WidgetChatListActionsEmojisAdapter(recycler).apply {
            onClickEmoji = ::addReaction
            onClickMoreEmojis = {
                currentModel?.message?.let { message ->
                    host().requestMessageReaction(message, host().messageActionCoordinator())
                    dismiss()
                }
            }
        }
        recycler.adapter = emojiAdapter
        recycler.itemAnimator = null
        dialog?.setTitle(R.string.message_actions_menu_label)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        hideKeyboard()
        Model.get(
            channelId,
            messageId,
            argumentsOrDefault.getCharSequence(ARG_CONTENT),
            argumentsOrDefault.getInt(ARG_TYPE, TYPE_CHAT),
        ).computationLatest().distinctUntilChanged().ui(this)
            .appSubscribe(
                WidgetChatListActions::class.java,
                subscriptionHandler = compositeSubscription::add,
                onNext = ::configureUI,
            )
    }

    override fun onDestroyView() {
        _binding?.dialogChatActionsAddReactionEmojisList?.let { recycler ->
            itemDecorator?.let(recycler::removeItemDecoration)
            recycler.adapter = null
        }
        itemDecorator = null
        emojiAdapter = null
        currentModel = null
        _binding = null
        super.onDestroyView()
    }

    private var currentModel: Model? = null

    private fun configureUI(model: Model?) {
        currentModel = model
        if (model == null) {
            dismiss()
            return
        }
        val binding = _binding ?: return
        val message = model.message
        val manage = model.manageMessageContext
        val isLocal = message.isLocal()
        val hasReactions = message.getReactionsMap().isNotEmpty()
        PluginContextMenuHost.bind(
            binding.pluginContextMenuItems,
            pluginContextMenuContext(
                PluginContextMenuSurface.MESSAGE,
                message.guildId,
                message.channelId,
                message.id,
                message.author?.id,
                message.id,
            ),
            ::dismiss,
        )

        configureQuickReactions(model.recentEmojis, isLocal, manage.canAddReactions)
        bind(binding.dialogChatActionsEdit, model.type == TYPE_CHAT && manage.canEdit && message.poll == null) {
            editMessage(model)
        }
        val canEndPoll = message.poll?.let { poll ->
            message.author?.id == model.me.id && poll.results?.finalized != true &&
                (poll.expiry?.dateTimeMillis ?: Long.MAX_VALUE) > System.currentTimeMillis()
        } == true
        bind(binding.dialogChatActionsEndPoll, canEndPoll) { endPoll(message) }
        bind(binding.dialogChatActionsPublish, canPublish(model)) {
            MessageActionDialogs.showPublishMessageConfirmation(parentFragmentManager, requireContext(), message, this) {
                dismiss()
            }
        }
        bind(binding.dialogChatActionsManageReactions, hasReactions) {
            WidgetManageReactions.create(channelId, messageId, requireContext())
        }
        bind(binding.dialogChatActionsRemoveAllReactions, hasReactions && manage.canManageMessages) {
            removeAllReactions(message)
        }
        bind(binding.dialogChatActionsResend, message.canResend()) {
            host().messageActionCoordinator().retry(message)
            dismiss()
        }
        bind(binding.dialogChatActionsMarkUnread, !message.isFailed() && manage.canMarkUnread) {
            StoreStream.getMessageAck().markUnread(message.channelId, message.id)
            dismiss()
        }
        bind(
            binding.dialogChatActionsCopy,
            !model.messageContent.isNullOrEmpty() && !message.isLocalApplicationCommand(),
        ) {
            val edited = EditedMessageNode.getEditedString(requireContext())
            copy(model.messageContent.toString().replace(edited, ""), R.string.copy_text)
            dismiss()
        }
        // Same shape the in-app router consumes (UriHandler.handleUriSpecialCase), so a pasted
        // copy jumps straight back to this message.
        bind(binding.dialogChatActionsCopyLink, !isLocal) {
            val guildSegment = model.channel?.guildId?.takeUnless { it == 0L } ?: "@me"
            copy(
                "https://discord.com/channels/$guildSegment/${message.channelId}/${message.id}",
                R.string.copy_message_link,
            )
            dismiss()
        }
        bind(binding.dialogChatActionsCopyId, model.isDeveloper && !isLocal) {
            copy(message.id.toString(), R.string.copy_id)
            dismiss()
        }
        bind(binding.dialogChatActionsCopyRaw, DeveloperSettings.isViewRawEnabled() && !message.content.isNullOrEmpty()) {
            copy(message.content.orEmpty(), R.string.view_raw_copy_raw)
            AppToast.show(this, R.string.view_raw_copied_content)
            dismiss()
        }
        bind(binding.dialogChatActionsViewRaw, DeveloperSettings.isViewRawEnabled() && !isLocal) {
            WidgetViewRaw.launch(requireContext(), message.channelId, message.id)
            dismiss()
        }
        // Aliucord's way to share a theme: a JSON file posted in a channel, installed from here.
        val themeFile = message.attachments.orEmpty().firstOrNull { it.filename.endsWith(".json", ignoreCase = true) }
        bind(binding.dialogChatActionsInstallTheme, themeFile != null && ThemeRuntime.isSupported) {
            themeFile?.let { ThemeInstaller.install(requireContext(), it.url, it.filename.removeSuffix(".json")) }
            dismiss()
        }
        val messageCommands = StoreStream.getApplicationCommands()
            .getContextCommands(message.channelId, ApplicationCommandIndexCommand.TYPE_MESSAGE)
        bind(binding.dialogChatActionsApps, !isLocal && messageCommands.isNotEmpty()) {
            dismiss()
            WidgetContextCommandsSheet.show(parentFragmentManager, message.channelId, ApplicationCommandIndexCommand.TYPE_MESSAGE, message.id)
        }
        bind(binding.dialogChatActionsReport, message.author?.id != model.me.id) {
            WidgetMobileReports.launchMessageReport(requireContext(), message.id, channelId)
            dismiss()
        }
        bind(
            binding.dialogChatActionsShare,
            ForwardingPolicy.canForward(message, model.channel, model.permissions),
        ) { forward(message) }
        bind(binding.dialogChatActionsPin, manage.canTogglePinned) {
            MessageActionDialogs.showPinMessageConfirmation(parentFragmentManager, requireContext(), message, this) {
                dismiss()
            }
        }
        binding.dialogChatActionsPin
            .setText(if (message.pinned == true) R.string.unpin else R.string.pin_message)
        bind(binding.dialogChatActionsDelete, manage.canDelete) {
            MessageActionDialogs.showDeleteMessageConfirmation(parentFragmentManager, requireContext(), message) {
                dismiss()
            }
        }
        bind(binding.dialogChatActionsProfile, true) {
            dismiss()
            message.author?.id?.let { WidgetUserSheet.show(parentFragmentManager, it, channelId, model.guild?.id) }
        }
        bind(binding.dialogChatActionsReply, canReply(model)) {
            dismiss()
            model.channel?.let { host().messageActionCoordinator().reply(it, message, model.me.id) }
        }
        bind(binding.dialogChatActionsStartThread, canCreatePublicThread(model)) {
            dialog?.dismiss()
            dismiss()
            ChannelSelector.getInstance().openCreateThread(
                model.guild?.id ?: 0L,
                message.channelId,
                message.id,
                "Message",
            )
        }
        // The XML stays hidden until all rows reflect this message and its permissions.
        binding.dialogChatActionsContainer.isVisible = true
    }

    private fun configureQuickReactions(recent: List<Emoji>, isLocal: Boolean, canAdd: Boolean) {
        val recycler = binding.dialogChatActionsAddReactionEmojisList
        if (recent.isEmpty() || isLocal || !canAdd) {
            recycler.isVisible = false
            return
        }
        recycler.isVisible = true
        // Give the wrap-content row a real item height before its first layout.
        if (emojiAdapter?.itemCount == 0) {
            recycler.alpha = 0f
            recycler.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            emojiAdapter?.setData(recent, recent.size + 1)
        }
        recycler.doOnLayout {
            // Adapter changes during layout can leave an auto-measured row at its empty height.
            recycler.post {
                if (_binding?.dialogChatActionsAddReactionEmojisList !== recycler ||
                    currentModel?.recentEmojis != recent || !recycler.isVisible) return@post
                val size = resources.getDimensionPixelSize(R.dimen.chat_input_emoji_size)
                val gap = dpToPixels(8)
                val available = recycler.width - recycler.paddingStart - recycler.paddingEnd
                val count = minOf(recent.size + 1, maxOf(1, (available + gap) / (size + gap)))
                itemDecorator?.let(recycler::removeItemDecoration)
                val remaining = maxOf(available - ((count - 1) * gap + size * count), 0)
                val decoration = SpacingDecoration(gap + if (count > 1) remaining / (count - 1) else 0)
                recycler.addItemDecoration(decoration)
                itemDecorator = decoration
                emojiAdapter?.setData(recent, count)
                recycler.alpha = 1f
                recycler.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }
    }

    private fun addReaction(emoji: Emoji) {
        val model = currentModel ?: return
        RestAPI.api.addReaction(model.message.channelId, model.message.id, emoji.getReactionKey())
            .restSubscribeOn().ui(this)
            .appSubscribe(WidgetChatListActions::class.java, context = context) {
                StoreStream.getEmojis().onEmojiUsed(emoji, isReaction = true)
                dismiss()
            }
    }

    private fun editMessage(model: Model) {
        prepareMessageForEdit(model.message, model.channel, model.guild)
            .ui(this).appSubscribe(WidgetChatListActions::class.java) { message ->
                host().requestMessageEdit(message, host().messageActionCoordinator())
                dismiss()
            }
    }

    private fun endPoll(message: Message) {
        WidgetNoticeDialog.show(
            parentFragmentManager,
            getString(R.string.poll_end_confirm_title),
            getString(R.string.poll_end_confirm_body),
            getString(R.string.poll_end_now),
            getString(R.string.cancel),
            mapOf(R.id.notice_ok to {
                val api = RestAPI.apiOrNull
                if (api == null) {
                    AppToast.show(this, R.string.poll_end_failed)
                } else {
                    api.expirePoll(message.channelId, message.id)
                        .restSubscribeOn()
                        .ui(this)
                        .appSubscribe(
                            WidgetChatListActions::class.java,
                            context = context,
                            onCompleted = { dismiss() },
                            onNext = {},
                        )
                }
            }),
        )
    }

    private fun removeAllReactions(message: Message) {
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(R.string.remove_all_reactions_confirm_title),
            bodyText = getString(R.string.remove_all_reactions_confirm_body),
            okText = getString(R.string.remove_all_reactions),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { view ->
                RestAPI.api.removeAllReactions(message.channelId, message.id).restSubscribeOn().ui(this)
                    .appSubscribe(WidgetChatListActions::class.java, view.context) { dismiss() }
            }),
        )
    }

    private fun forward(message: Message) {
        val target = parentFragment ?: return
        dismiss()
        target.parentFragmentManager.commit {
            replace(target.id, WidgetIncomingShare.newForwardInstance(message))
            addToBackStack("forward_message")
        }
    }

    private fun canPublish(model: Model): Boolean =
        (model.manageMessageContext.canEdit || model.manageMessageContext.canManageMessages) &&
            model.channel?.type == Channel.GUILD_ANNOUNCEMENT && model.message.type == 0 && !model.message.isCrossposted()

    private fun canReply(model: Model): Boolean {
        val channel = model.channel ?: return false
        val message = model.message
        val privateChannel = channel.isPrivate() && !channel.isSystemDM()
        val canReadAndSend = PermissionUtils.can(
            Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES or Permission.READ_MESSAGE_HISTORY,
            model.permissions,
        )
        return !message.isLocal() && model.type == TYPE_CHAT &&
            message.type in MessageTypes.REPLYABLE && (privateChannel || canReadAndSend)
    }

    private fun canCreatePublicThread(model: Model): Boolean {
        val channel = model.channel ?: return false
        val guild = model.guild ?: return false
        val permissions = model.permissions
        val permissionAllowed = PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions) &&
            PermissionUtils.can(Permission.MANAGE_MESSAGES, permissions)
        val channelAllowed = !model.message.isSystemMessage() && !channel.isThread() &&
            !model.message.hasThread() && !channel.isVoiceChannel()
        return permissionAllowed && channelAllowed
    }

    private fun bind(view: View, visible: Boolean, click: () -> Unit) =
        view.apply {
            isVisible = visible
            setOnClickListener { click() }
        }

    private fun copy(text: CharSequence, label: Int) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(label), text))
    }

    private fun host(): Host = (parentFragment as? Host)
        ?: parentFragmentManager.fragments.lastOrNull { it is Host } as? Host
        ?: (activity as? Host)
        ?: error("WidgetChatListActions host must implement Host")

    private class SpacingDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            if (parent.getChildAdapterPosition(view) > 0) outRect.left = space
        }
    }

    data class Model(
        val message: Message,
        val guild: Guild?,
        val messageAuthorName: String,
        val messageContent: CharSequence?,
        val manageMessageContext: ManageMessageContext,
        val type: Int,
        val isDeveloper: Boolean,
        val recentEmojis: List<Emoji>,
        val channel: Channel?,
        val permissions: Long?,
        val me: MeUser,
    ) {
        companion object {
            fun get(channelId: Long, messageId: Long, messageContent: CharSequence?, type: Int): Observable<Model?> {
                val message = when (type) {
                    TYPE_CHAT -> StoreStream.getMessages().observeMessagesForChannel(channelId, messageId)
                    TYPE_PINS -> StoreStream.getPinnedMessages().observePinnedMessage(channelId, messageId)
                    else -> Observable.just(null)
                }
                return Observable.combineLatest(message, StoreStream.getChannels().observeChannel(channelId), ::Pair)
                    .switchMap { (currentMessage, channel) ->
                        if (currentMessage == null || channel == null) return@switchMap Observable.just<Model?>(null)
                        val authorId = currentMessage.author?.id ?: 0L
                        val identity = Observable.combineLatest(
                            StoreStream.getPermissions().observePermissionsForChannel(channelId),
                            StoreStream.getUsers().observeMe(),
                            StoreStream.getGuilds().observeComputed(channel.guildId, listOf(authorId)),
                        ) { permissions, me, members -> Triple(permissions, me, members[authorId]) }
                        val context = Observable.combineLatest(
                            StoreStream.getGuilds().observeGuild(channel.guildId),
                            StoreStream.getEmojis()
                                .getEmojiSet(channel.guildId, channel.id, forReaction = true),
                            ::Pair,
                        )
                        Observable.combineLatest(identity, context) { (permissions, me, member), (guild, emojis) ->
                            create(currentMessage, guild, permissions, me, member, channel, messageContent, type, emojis)
                        }
                    }
            }

            private fun create(
                message: Message,
                guild: Guild?,
                permissions: Long?,
                me: MeUser,
                member: GuildMember?,
                channel: Channel,
                messageContent: CharSequence?,
                type: Int,
                emojis: EmojiSet,
            ) = Model(
                message = message,
                guild = guild,
                messageAuthorName = message.author?.getDisplayName(member?.identityNick()).orEmpty(),
                messageContent = messageContent ?: message.content,
                manageMessageContext = ManageMessageContext.from(
                    message,
                    permissions,
                    me,
                    guild?.mfaLevel,
                    channel.isPrivate(),
                    channel.isSystemDM(),
                    channel.isArchivedThread(),
                ),
                type = type,
                isDeveloper = StoreStream.getUserSettings().getIsDeveloperMode(),
                recentEmojis = emojis.recentEmojis,
                channel = channel,
                permissions = permissions,
                me = me,
            )
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "INTENT_EXTRA_MESSAGE_CHANNEL_ID"
        private const val ARG_MESSAGE_ID = "INTENT_EXTRA_MESSAGE_ID"
        private const val ARG_CONTENT = "INTENT_EXTRA_MESSAGE_CONTENT"
        private const val ARG_TYPE = "INTENT_EXTRA_TYPE"
        private const val TYPE_CHAT = 0
        private const val TYPE_PINS = 1

        fun showForChat(fragmentManager: FragmentManager, channelId: Long, messageId: Long, messageContent: CharSequence) =
            show(fragmentManager, channelId, messageId, messageContent, TYPE_CHAT)

        fun showForPin(fragmentManager: FragmentManager, channelId: Long, messageId: Long, messageContent: CharSequence) =
            show(fragmentManager, channelId, messageId, messageContent, TYPE_PINS)

        fun show(fragmentManager: FragmentManager, channelId: Long, messageId: Long, _input: MessageActions.Input) =
            show(fragmentManager, channelId, messageId, null, TYPE_CHAT)

        fun showForPin(fragmentManager: FragmentManager, channelId: Long, messageId: Long, _input: MessageActions.Input) =
            show(fragmentManager, channelId, messageId, null, TYPE_PINS)

        private fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            messageId: Long,
            messageContent: CharSequence?,
            type: Int,
        ) = WidgetChatListActions().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putLong(ARG_MESSAGE_ID, messageId)
                putCharSequence(ARG_CONTENT, messageContent)
                putInt(ARG_TYPE, type)
            }
        }.show(fragmentManager, WidgetChatListActions::class.java.name)

        internal fun prepareMessageForEdit(message: Message, channel: Channel?, guild: Guild?): Observable<Message> =
            StoreStream.getEmojis().getEmojiSet(channel?.guildId ?: 0L, message.channelId).take(1)
                .computationLatest().map { emojiSet ->
                    message.copy(
                        content = unparse(
                            message.content.orEmpty(),
                            guild,
                            StoreStream.getChannels().getChannelsForGuild(channel?.guildId ?: 0L),
                            StoreStream.getUsers().getUsers(),
                            emojiSet,
                        ),
                    )
                }

        private fun unparse(
            raw: String,
            guild: Guild?,
            channels: Map<Long, Channel>,
            users: Map<Long, com.discord.models.user.User>,
            emojis: EmojiSet,
        ): String {
            var result = USER_MENTION.replace(raw) { match ->
                users[match.groupValues[1].toLongOrNull()]?.let { "@${it.username}${it.discriminatorWithPadding}" }
                    ?: match.value
            }
            result = ROLE_MENTION.replace(result) { match ->
                guild?.roles?.firstOrNull { it.id == match.groupValues[1].toLongOrNull() }?.let { "@${it.name}" }
                    ?: match.value
            }
            result = CHANNEL_MENTION.replace(result) { match ->
                channels[match.groupValues[1].toLongOrNull()]?.let { "#${it.getDisplayName()}" } ?: match.value
            }
            return CUSTOM_EMOJI.replace(result) { match ->
                val emoji = emojis.emojiIndex[match.groupValues[3]]
                ":${emoji?.firstName ?: match.groupValues[2]}:"
            }
        }

        private val USER_MENTION = Regex("<@!?(\\d+)>")
        private val ROLE_MENTION = Regex("<@&?(\\d+)>")
        private val CHANNEL_MENTION = Regex("<#?(\\d+)>")
        private val CUSTOM_EMOJI = Regex("<(a)?:([A-Za-z0-9_]+):(\\d+)>")
    }
}
