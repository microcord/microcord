package com.discord.widgets.chat.list.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
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
import com.discord.widgets.chat.list.CreateThreadsFeatureFlag
import com.discord.widgets.chat.list.NewThreadsPermissionsFeatureFlag
import com.discord.widgets.chat.list.ViewThreadsFeatureFlag
import com.discord.widgets.chat.list.reactions.WidgetManageReactions
import com.discord.widgets.mobile_reports.ReportsFeatureFlag
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.tos.WidgetTosReportViolation
import com.discord.widgets.user.usersheet.WidgetUserSheet
import rx.Observable
import rx.subscriptions.CompositeSubscription

class WidgetChatListActions : AppBottomSheet() {
    interface Host {
        fun messageActionCoordinator(): MessageActionCoordinator
        fun messageForAction(channelId: Long, messageId: Long): Message?
        fun channelForAction(channelId: Long): Channel?
        fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator)
        fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator)
        fun onMessageActionError(error: Throwable) = Unit
    }

    private lateinit var emojiAdapter: WidgetChatListActionsEmojisAdapter
    private var itemDecorator: RecyclerView.ItemDecoration? = null
    private var channelId = 0L
    private var messageId = 0L

    override fun getContentViewResId() = R.layout.widget_chat_list_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID)
        messageId = argumentsOrDefault.getLong(ARG_MESSAGE_ID)
        val recycler = view.findViewById<RecyclerView>(R.id.dialog_chat_actions_add_reaction_emojis_list)
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

    private var currentModel: Model? = null

    private fun configureUI(model: Model?) {
        currentModel = model
        if (model == null) {
            dismiss()
            return
        }
        val root = requireView()
        val message = model.message
        val manage = model.manageMessageContext
        val isLocal = message.isLocal()
        val hasReactions = message.getReactionsMap().isNotEmpty()

        configureQuickReactions(root, model.recentEmojis, isLocal, manage.canAddReactions)
        bind(root, R.id.dialog_chat_actions_edit, model.type == TYPE_CHAT && manage.canEdit) { editMessage(model) }
        bind(root, R.id.dialog_chat_actions_publish, canPublish(model)) {
            MessageActionDialogs.showPublishMessageConfirmation(parentFragmentManager, requireContext(), message, this) {
                dismiss()
            }
        }
        bind(root, R.id.dialog_chat_actions_manage_reactions, hasReactions) {
            WidgetManageReactions.create(channelId, messageId, requireContext())
        }
        bind(root, R.id.dialog_chat_actions_remove_all_reactions, hasReactions && manage.canManageMessages) {
            removeAllReactions(message)
        }
        bind(root, R.id.dialog_chat_actions_resend, message.canResend()) {
            host().messageActionCoordinator().retry(message)
            dismiss()
        }
        bind(root, R.id.dialog_chat_actions_mark_unread, !message.isFailed() && manage.canMarkUnread) {
            StoreStream.getMessageAck().markUnread(message.channelId, message.id)
            dismiss()
        }
        bind(
            root,
            R.id.dialog_chat_actions_copy,
            !model.messageContent.isNullOrEmpty() && !message.isLocalApplicationCommand(),
        ) {
            val edited = EditedMessageNode.getEditedString(requireContext())
            copy(model.messageContent.toString().replace(edited, ""), R.string.copy_text)
            dismiss()
        }
        bind(root, R.id.dialog_chat_actions_copy_id, model.isDeveloper && !isLocal) {
            copy(message.id.toString(), R.string.copy_id)
            dismiss()
        }
        bind(root, R.id.dialog_chat_actions_report, message.author?.id != model.me.id) {
            if (ReportsFeatureFlag.isEnabled()) {
                WidgetMobileReports.launchMessageReport(requireContext(), message.id, channelId)
            } else {
                WidgetTosReportViolation.show(requireContext(), model.messageAuthorName, message.channelId, message.id)
            }
            dismiss()
        }
        bind(root, R.id.dialog_chat_actions_share, model.isDeveloper && !isLocal) {
            share(model)
            dismiss()
        }
        bind(root, R.id.dialog_chat_actions_pin, manage.canTogglePinned) {
            MessageActionDialogs.showPinMessageConfirmation(parentFragmentManager, requireContext(), message, this) {
                dismiss()
            }
        }
        root.findViewById<TextView>(R.id.dialog_chat_actions_pin)
            .setText(if (message.pinned == true) R.string.unpin else R.string.pin_message)
        bind(root, R.id.dialog_chat_actions_delete, manage.canDelete) {
            MessageActionDialogs.showDeleteMessageConfirmation(parentFragmentManager, requireContext(), message) {
                dismiss()
            }
        }
        bind(root, R.id.dialog_chat_actions_profile, true) {
            dismiss()
            message.author?.id?.let { WidgetUserSheet.show(parentFragmentManager, it, channelId, model.guild?.id) }
        }
        bind(root, R.id.dialog_chat_actions_reply, canReply(model)) {
            dismiss()
            model.channel?.let { host().messageActionCoordinator().reply(it, message, model.me.id) }
        }
        bind(root, R.id.dialog_chat_actions_start_thread, canCreatePublicThread(model)) {
            dismiss()
            ChannelSelector.getInstance().openCreateThread(
                model.guild?.id ?: 0L,
                message.channelId,
                message.id,
                "Message",
            )
        }
    }

    private fun configureQuickReactions(root: View, recent: List<Emoji>, isLocal: Boolean, canAdd: Boolean) {
        val recycler = root.findViewById<RecyclerView>(R.id.dialog_chat_actions_add_reaction_emojis_list)
        if (recent.isEmpty() || isLocal || !canAdd) {
            recycler.isVisible = false
            return
        }
        recycler.isVisible = true
        recycler.post {
            val size = resources.getDimensionPixelSize(R.dimen.chat_input_emoji_size)
            val gap = dpToPixels(8)
            val available = recycler.width - recycler.paddingStart - recycler.paddingEnd
            val count = minOf(recent.size + 1, maxOf(1, (available + gap) / (size + gap)))
            itemDecorator?.let(recycler::removeItemDecoration)
            val remaining = maxOf(available - ((count - 1) * gap + size * count), 0)
            val decoration = SpacingDecoration(gap + if (count > 1) remaining / (count - 1) else 0)
            recycler.addItemDecoration(decoration)
            itemDecorator = decoration
            emojiAdapter.setData(recent, count)
        }
    }

    private fun addReaction(emoji: Emoji) {
        val model = currentModel ?: return
        RestAPI.api.addReaction(model.message.channelId, model.message.id, emoji.getReactionKey())
            .restSubscribeOn().ui(this)
            .appSubscribe(WidgetChatListActions::class.java, context = context) {
                StoreStream.getEmojis().onEmojiUsed(emoji)
                dismiss()
            }
    }

    private fun editMessage(model: Model) {
        val message = model.message
        StoreStream.getEmojis().getEmojiSet(model.channel?.guildId ?: 0L, message.channelId).take(1)
            .computationLatest().ui(this)
            .appSubscribe(WidgetChatListActions::class.java) { emojiSet ->
                val content = unparse(
                    message.content.orEmpty(),
                    model.guild,
                    StoreStream.getChannels().getChannelsForGuild(model.channel?.guildId ?: 0L),
                    StoreStream.getUsers().getUsers(),
                    emojiSet,
                )
                host().requestMessageEdit(message.copy(content = content), host().messageActionCoordinator())
                dismiss()
            }
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

    private fun share(model: Model) {
        val guild = model.guild?.id?.toString() ?: "@me"
        val url = "https://discord.com/channels/$guild/${model.message.channelId}/${model.message.id}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }, getString(R.string.share)))
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
            (message.isUserMessage() || message.isApplicationMessage()) && (privateChannel || canReadAndSend)
    }

    private fun canCreatePublicThread(model: Model): Boolean {
        val channel = model.channel ?: return false
        val guild = model.guild ?: return false
        val permissions = model.permissions
        val permissionAllowed = PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions) &&
            PermissionUtils.can(Permission.MANAGE_MESSAGES, permissions) &&
            (NewThreadsPermissionsFeatureFlag.isEnabled(guild.id) || PermissionUtils.hasAccessWrite(channel, permissions))
        val channelAllowed = !model.message.isSystemMessage() && !channel.isThread() &&
            !model.message.hasThread() && !channel.isVoiceChannel()
        return ViewThreadsFeatureFlag.isEnabled() && CreateThreadsFeatureFlag.isEnabled(guild.id) &&
            permissionAllowed && channelAllowed
    }

    private fun bind(root: View, id: Int, visible: Boolean, click: () -> Unit) =
        root.findViewById<View>(id).apply {
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
                            StoreStream.getEmojis().getEmojiSet(channel.guildId, channel.id),
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
                messageAuthorName = member?.nick ?: message.author?.username.orEmpty(),
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
