package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.utilities.time.TimeUtils
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.TimestampEntry

/**
 * The date separator between two days of history.
 *
 * Shares `widget_chat_list_adapter_item_text_divider` with the unread marker; the two differ only
 * in the colour they paint the rule and label, which is why the tinting happens once in `init`.
 */
internal class WidgetChatListAdapterItemTimestamp(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_text_divider, adapter) {

    private val strokeLeft: View = itemView.findViewById(R.id.divider_stroke_left)
    private val strokeRight: View = itemView.findViewById(R.id.divider_stroke_right)
    private val dividerText: TextView = itemView.findViewById(R.id.divider_text)

    init {
        val muted = ThemedColor.resolve(itemView.context, R.attr.colorTextMuted)
        strokeLeft.setBackgroundColor(muted)
        strokeRight.setBackgroundColor(muted)
        dividerText.setTextColor(muted)
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        dividerText.text = TimeUtils.renderUtcDate(
            (data as TimestampEntry).timestamp,
            dividerText.context,
        )
    }
}
