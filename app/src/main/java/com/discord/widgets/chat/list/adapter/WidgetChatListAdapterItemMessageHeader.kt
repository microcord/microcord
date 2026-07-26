package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.Space
import android.widget.TextView
import com.discord.R
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageHeaderEntry

/**
 * Context header above a pinned message or a search result, naming the channel (and guild) the
 * message came from.
 *
 * The header is also the separator between results, so the hairline is drawn for every occurrence
 * except the first; the first one gets a plain spacer instead, since there is nothing above it.
 */
class WidgetChatListAdapterItemMessageHeader(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_message_header, adapter) {

    private val channelName: TextView = itemView.findViewById(R.id.chat_list_item_message_header_channel)
    private val divider: View = itemView.findViewById(R.id.chat_list_item_message_header_divider)
    private val guildName: TextView = itemView.findViewById(R.id.chat_list_item_message_header_guild)
    private val spacer: Space = itemView.findViewById(R.id.chat_list_item_message_header_spacer)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as MessageHeaderEntry
        val isFirstRow = position <= 0
        divider.visibility = if (isFirstRow) View.GONE else View.VISIBLE
        spacer.visibility = if (isFirstRow) View.VISIBLE else View.GONE
        channelName.text = entry.channelName
        guildName.text = entry.text
    }
}
