package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.utilities.locale.LocaleManager

/**
 * The red "NEW MESSAGES" rule marking the first message the viewer has not read.
 *
 * The row carries no per-message state — the entry only ever exists at one position at a time — so
 * everything is configured once, in `init`, and `onConfigure` is not overridden.
 */
class WidgetChatListAdapterItemNewMessages(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_text_divider, adapter) {

    private val localeManager = LocaleManager()

    init {
        val strokeLeft: View = itemView.findViewById(R.id.divider_stroke_left)
        val strokeRight: View = itemView.findViewById(R.id.divider_stroke_right)
        val dividerText: TextView = itemView.findViewById(R.id.divider_text)

        val red = ContextCompat.getColor(itemView.context, R.color.status_red_500)
        strokeLeft.setBackgroundColor(red)
        strokeRight.setBackgroundColor(red)
        dividerText.setTextColor(red)

        // Upper-cased in code rather than with `textAllCaps`, so the user's primary locale decides
        // the casing rules (Turkish dotted/dotless i, for instance).
        dividerText.text = itemView.context.getString(R.string.new_messages_divider)
            .uppercase(localeManager.getPrimaryLocale(dividerText.context))
    }
}
