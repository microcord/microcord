package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.google.android.material.button.MaterialButton

/**
 * The system message a new guild posts reminding the viewer to invite people.
 *
 * It is a real message — long-pressing it opens the ordinary message actions — but it renders as a
 * call-to-action card, and its copy is addressed to the owner or to a member depending on who is
 * reading it.
 */
class WidgetChatListAdapterItemGuildInviteReminder(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_guild_invite_reminder, adapter) {

    private val title: TextView = itemView.findViewById(R.id.chat_action_item_text)
    private val description: TextView = itemView.findViewById(R.id.chat_action_item_subtext)
    private val button: MaterialButton = itemView.findViewById(R.id.chat_action_item_button)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as? MessageEntry ?: return

        val isOwner = adapter.data.guild?.isOwner(adapter.data.userId) == true
        title.setText(
            if (isOwner) R.string.system_message_invite_users_owner else R.string.system_message_invite_users,
        )
        description.setText(
            if (isOwner) {
                R.string.system_message_invite_users_description_owner
            } else {
                R.string.system_message_invite_users_description
            },
        )

        button.setOnClickListener { view ->
            ChatListRowNavigation.launchInviteShare
                ?.invoke(view.context, adapter.fragmentManager, adapter.data.guildId, null, INVITE_SOURCE)
        }

        // The action sheet wants the message as text. The reference always hands it the owner
        // wording, whichever variant is on screen, so copy-message produces a stable string.
        itemView.setOnLongClickListenerConsumeClick {
            adapter.eventHandler.onMessageLongClicked(
                entry.message,
                itemView.context.getString(R.string.system_message_invite_users_owner),
                false,
            )
        }
    }

    private companion object {
        private const val INVITE_SOURCE = "Welcome Message"
    }
}
