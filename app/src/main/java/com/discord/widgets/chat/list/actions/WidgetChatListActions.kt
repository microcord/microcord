package com.discord.widgets.chat.list.actions

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.permissions.ManageMessageContext
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.widgets.chat.list.reactions.WidgetManageReactions
import com.discord.widgets.mobile_reports.ReportsFeatureFlag
import com.discord.widgets.mobile_reports.WidgetMobileReports
import com.discord.widgets.tos.WidgetTosReportViolation
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class WidgetChatListActions : BottomSheetDialogFragment() {
    interface Host {
        fun messageActionCoordinator(): MessageActionCoordinator
        fun messageForAction(channelId: Long, messageId: Long): Message?
        fun channelForAction(channelId: Long): Channel?
        fun requestMessageEdit(message: Message, coordinator: MessageActionCoordinator)
        fun requestMessageReaction(message: Message, coordinator: MessageActionCoordinator)
        fun onMessageActionError(error: Throwable) = Unit
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        val view = LayoutInflater.from(context).inflate(R.layout.widget_chat_list_actions, null)
        dialog.setContentView(view)
        val input = requireArguments().getSerializable(ARG_INPUT) as MessageActions.Input
        val channelId = requireArguments().getLong(ARG_CHANNEL_ID)
        val messageId = requireArguments().getLong(ARG_MESSAGE_ID)
        val type = requireArguments().getInt(ARG_TYPE, TYPE_CHAT)
        val host = host()
        val message = host.messageForAction(channelId, messageId)
        val channel = host.channelForAction(channelId)
        if (message == null || channel == null) {
            dismissAllowingStateLoss()
            return dialog
        }

        val coordinator = host.messageActionCoordinator()
        val me = StoreStream.getUsers().getMe()
        val guild = StoreStream.getGuilds().getGuild(channel.guildId)
        val manage = ManageMessageContext.from(
            message,
            input.permissions,
            me,
            guild?.mfaLevel,
            channel.isPrivate(),
            channel.isSystemDM(),
            channel.isArchivedThread(),
        )
        val availability = MessageActions.evaluate(
            input.copy(
                canEdit = manage.canEdit && type == TYPE_CHAT,
                canDelete = manage.canDelete,
                canManageMessages = manage.canManageMessages,
                canAddReactions = manage.canAddReactions,
                canTogglePinned = manage.canTogglePinned,
                canMarkUnread = manage.canMarkUnread,
                hasReactions = message.reactions.orEmpty().isNotEmpty(),
                isDeveloper = StoreStream.getUserSettings().getIsDeveloperMode(),
                isFailed = message.isFailed(),
            ),
        )

        bind(view, R.id.dialog_chat_actions_reply, availability, MessageActions.Action.REPLY) {
            coordinator.reply(channel, message, input.currentUserId)
        }
        bind(view, R.id.dialog_chat_actions_edit, availability, MessageActions.Action.EDIT) {
            host.requestMessageEdit(message, coordinator)
        }
        bind(view, R.id.dialog_chat_actions_delete, availability, MessageActions.Action.DELETE) {
            confirm(R.string.delete_message_title, R.string.delete_message_body, R.string.delete) {
                coordinator.delete(message) { host.reportFailure(it) }
            }
        }
        bind(view, R.id.dialog_chat_actions_copy, availability, MessageActions.Action.COPY) {
            copy(message.content.orEmpty(), R.string.copy_text)
        }
        bind(view, R.id.dialog_chat_actions_pin, availability, MessageActions.Action.PIN) {
            confirm(R.string.pin_message, R.string.pin_message_body_mobile, R.string.pin_confirm) {
                coordinator.setPinned(message, true) { host.reportFailure(it) }
            }
        }
        if (availability.getValue(MessageActions.Action.UNPIN).visible) {
            view.findViewById<TextView>(R.id.dialog_chat_actions_pin).setText(R.string.unpin)
            bind(view, R.id.dialog_chat_actions_pin, availability, MessageActions.Action.UNPIN) {
                confirm(R.string.unpin_message, R.string.unpin_message_body, R.string.unpin_confirm) {
                    coordinator.setPinned(message, false) { host.reportFailure(it) }
                }
            }
        }
        bind(view, R.id.dialog_chat_actions_resend, availability, MessageActions.Action.RETRY) {
            coordinator.retry(message)
        }
        bind(view, R.id.dialog_chat_actions_profile, availability, MessageActions.Action.PROFILE) {
            message.author?.id?.let { WidgetUserSheet.show(parentFragmentManager, it, channelId, channel.guildId) }
        }
        bind(
            view,
            R.id.dialog_chat_actions_manage_reactions,
            availability,
            MessageActions.Action.MANAGE_REACTIONS,
        ) { WidgetManageReactions.create(channelId, messageId, requireContext()) }
        bind(
            view,
            R.id.dialog_chat_actions_remove_all_reactions,
            availability,
            MessageActions.Action.REMOVE_ALL_REACTIONS,
        ) {
            confirm(
                R.string.remove_all_reactions_confirm_title,
                R.string.remove_all_reactions_confirm_body,
                R.string.remove_all_reactions,
            ) {
                RestAPI.api.removeAllReactions(channelId, messageId)
                    .restSubscribeOn()
                    .appSubscribe(WidgetChatListActions::class.java, context = context) { dismiss() }
            }
        }
        bind(view, R.id.dialog_chat_actions_mark_unread, availability, MessageActions.Action.MARK_UNREAD) {
            StoreStream.getMessageAck().markUnread(channelId, messageId)
        }
        bind(view, R.id.dialog_chat_actions_copy_id, availability, MessageActions.Action.COPY_ID) {
            copy(message.id.toString(), R.string.copy_id)
        }
        bind(view, R.id.dialog_chat_actions_report, availability, MessageActions.Action.REPORT) {
            val authorName = message.author?.username.orEmpty()
            if (ReportsFeatureFlag.isEnabled()) {
                WidgetMobileReports.launchMessageReport(requireContext(), messageId, channelId)
            } else {
                WidgetTosReportViolation.show(requireContext(), authorName, channelId, messageId)
            }
        }

        configureQuickReactions(view, channel, message, manage.canAddReactions, coordinator, host)
        listOf(
            R.id.dialog_chat_actions_start_thread,
            R.id.dialog_chat_actions_publish,
            R.id.dialog_chat_actions_share,
        ).forEach { view.findViewById<View>(it).isVisible = false }
        dialog.setTitle(R.string.message_actions_menu_label)
        return dialog
    }

    private fun configureQuickReactions(
        root: View,
        channel: Channel,
        message: Message,
        canAddReactions: Boolean,
        coordinator: MessageActionCoordinator,
        host: Host,
    ) {
        val recycler = root.findViewById<RecyclerView>(R.id.dialog_chat_actions_add_reaction_emojis_list)
        if (message.isLocal() || !canAddReactions) {
            recycler.isVisible = false
            return
        }
        val adapter = WidgetChatListActionsEmojisAdapter(recycler).apply {
            onClickEmoji = { emoji ->
                coordinator.toggleReaction(message, emoji.toReactionEmoji()) { host.reportFailure(it) }
                StoreStream.getEmojis().onEmojiUsed(emoji)
                dismiss()
            }
            onClickMoreEmojis = {
                host.requestMessageReaction(message, coordinator)
                dismiss()
            }
        }
        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.adapter = adapter
        StoreStream.getEmojis().getEmojiSet(channel.guildId, channel.id)
            .take(1)
            .subscribe { emojiSet ->
                recycler.post {
                    val size = resources.getDimensionPixelSize(R.dimen.chat_input_emoji_size)
                    val gap = dpToPixels(8)
                    val width = recycler.width - recycler.paddingStart - recycler.paddingEnd
                    val count = minOf(emojiSet.recentEmojis.size + 1, maxOf(1, (width + gap) / (size + gap)))
                    adapter.setData(emojiSet.recentEmojis, count)
                    recycler.isVisible = count > 0
                    recycler.addItemDecoration(SpacingDecoration(gap))
                }
            }
    }

    private fun Emoji.toReactionEmoji(): MessageReactionEmoji = when (this) {
        is ModelEmojiCustom -> MessageReactionEmoji(idStr, name, isAnimated)
        else -> MessageReactionEmoji(null, getReactionKey(), false)
    }

    private fun bind(
        root: View,
        id: Int,
        states: Map<MessageActions.Action, MessageActions.Availability>,
        action: MessageActions.Action,
        click: () -> Unit,
    ) = root.findViewById<View>(id).apply {
        val state = states.getValue(action)
        isVisible = state.visible
        isEnabled = state.enabled
        setOnClickListener { click(); dismiss() }
    }

    private fun copy(text: CharSequence, label: Int) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(label), text))
    }

    private fun host(): Host = (parentFragment as? Host) ?: (activity as? Host)
        ?: error("WidgetChatListActions host must implement Host")

    private fun Host.reportFailure(result: Result<Unit>) = result.exceptionOrNull()?.let(::onMessageActionError)

    private fun confirm(title: Int, message: Int, positive: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive) { _, _ -> action() }
            .show()
    }

    private class SpacingDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            if (parent.getChildAdapterPosition(view) > 0) outRect.left = space
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_INPUT = "policy_input"
        private const val ARG_TYPE = "type"
        private const val TYPE_CHAT = 0
        private const val TYPE_PINS = 1

        fun show(fragmentManager: FragmentManager, channelId: Long, messageId: Long, input: MessageActions.Input) =
            show(fragmentManager, channelId, messageId, input, TYPE_CHAT)

        fun showForPin(
            fragmentManager: FragmentManager,
            channelId: Long,
            messageId: Long,
            input: MessageActions.Input,
        ) = show(fragmentManager, channelId, messageId, input, TYPE_PINS)

        private fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            messageId: Long,
            input: MessageActions.Input,
            type: Int,
        ) {
            WidgetChatListActions().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_MESSAGE_ID, messageId)
                    putSerializable(ARG_INPUT, input)
                    putInt(ARG_TYPE, type)
                }
            }.show(fragmentManager, WidgetChatListActions::class.java.name)
        }
    }
}
