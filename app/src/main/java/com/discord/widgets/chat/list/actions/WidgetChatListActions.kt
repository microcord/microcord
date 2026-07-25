package com.discord.widgets.chat.list.actions

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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
        val host = host()
        val message = host.messageForAction(channelId, messageId)
        val channel = host.channelForAction(channelId)
        if (message == null) {
            dismissAllowingStateLoss()
            return dialog
        }
        val coordinator = host.messageActionCoordinator()
        val availability = MessageActions.evaluate(input)

        bind(view, R.id.dialog_chat_actions_reply, availability, MessageActions.Action.REPLY) {
            channel?.let { coordinator.reply(it, message, input.currentUserId) }
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
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_text), message.content.orEmpty()))
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
        val cancel = TextView(requireContext()).apply {
            setText(R.string.cancel)
            contentDescription = getString(R.string.cancel)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val state = availability.getValue(MessageActions.Action.CANCEL)
            isVisible = state.visible
            isEnabled = state.enabled
            setOnClickListener { coordinator.cancel(message); dismiss() }
        }
        view.findViewById<ViewGroup>(R.id.dialog_chat_actions_container).addView(cancel)

        val reactionState = availability.getValue(MessageActions.Action.ADD_REACTION)
        view.findViewById<RecyclerView>(R.id.dialog_chat_actions_add_reaction_emojis_list).apply {
            isVisible = reactionState.visible
            isEnabled = reactionState.enabled
            adapter = ActionAdapter(getString(R.string.add_reaction), reactionState.enabled) {
                host.requestMessageReaction(message, coordinator)
                dismiss()
            }
        }
        hideUnused(view)
        dialog.setTitle(R.string.message_actions_menu_label)
        return dialog
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

    private fun hideUnused(root: View) {
        listOf(
            R.id.dialog_chat_actions_start_thread, R.id.dialog_chat_actions_profile,
            R.id.dialog_chat_actions_manage_reactions, R.id.dialog_chat_actions_remove_all_reactions,
            R.id.dialog_chat_actions_publish, R.id.dialog_chat_actions_share,
            R.id.dialog_chat_actions_mark_unread, R.id.dialog_chat_actions_copy_id,
            R.id.dialog_chat_actions_report,
        ).forEach { root.findViewById<View>(it).isVisible = false }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class ActionAdapter(
        private val label: String,
        private val enabled: Boolean,
        private val click: () -> Unit,
    ) :
        RecyclerView.Adapter<ActionAdapter.Holder>() {
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            isFocusable = true
            isEnabled = enabled
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = label
            holder.text.contentDescription = label
            holder.text.setOnClickListener(if (enabled) View.OnClickListener { click() } else null)
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_INPUT = "policy_input"

        fun show(fragmentManager: FragmentManager, channelId: Long, messageId: Long, input: MessageActions.Input) {
            WidgetChatListActions().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_MESSAGE_ID, messageId)
                    putSerializable(ARG_INPUT, input)
                }
            }.show(fragmentManager, WidgetChatListActions::class.java.name)
        }
    }
}
