package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmptyPinsEntry

/**
 * Empty state for the pinned-messages surface.
 *
 * The copy is supplied by the entry rather than the row, because the pins screen phrases it
 * differently depending on whether the viewer may pin messages here.
 */
class WidgetChatListAdapterItemEmptyPins(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_empty_pins, adapter) {

    private val emptyText: TextView = itemView.findViewById(R.id.chat_list_empty_pins_text)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        emptyText.text = (data as EmptyPinsEntry).text
    }
}
