package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.widgets.chat.list.entries.BlockedMessagesEntry
import com.discord.widgets.chat.list.entries.ChatListEntry

/**
 * The collapsed "N Blocked Messages" bar that stands in for a run of consecutive messages from
 * blocked users. Tapping it asks the host to expand (or re-collapse) the run.
 */
class WidgetChatListAdapterItemBlocked(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_blocked, adapter) {

    private val blockedText: TextView = itemView.findViewById(R.id.chat_list_adapter_item_blocked)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as BlockedMessagesEntry
        blockedText.text = blockedText(entry.blockedCount, entry.isIgnored)
        itemView.setOnClickListener {
            adapter.eventHandler.onMessageBlockedGroupClicked(entry.message)
        }
    }

    /**
     * `blocked_messages` is a single `{count}` token whose value is itself a pluralised phrase, so
     * the plural is resolved first and then substituted.
     */
    private fun blockedText(blockedCount: Int, isIgnored: Boolean): CharSequence {
        val phrase = itemView.i18nPlural(
            if (isIgnored) R.plurals.ignored_messages_count else R.plurals.blocked_messages_count,
            blockedCount,
            "count" to blockedCount,
        )
        return itemView.i18nFormat(R.string.blocked_messages, "count" to phrase)
    }
}
