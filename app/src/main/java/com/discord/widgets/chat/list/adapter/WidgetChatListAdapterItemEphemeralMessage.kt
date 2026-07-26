package com.discord.widgets.chat.list.adapter

import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EphemeralMessageEntry

/**
 * The "only you can see this" footer under an interaction response.
 *
 * An ephemeral message exists only on this client: nobody else received it, and it disappears on
 * its own. The row therefore offers a Dismiss link — which removes the message locally rather than
 * deleting anything server-side — and a help icon explaining why the message looks different.
 */
class WidgetChatListAdapterItemEphemeralMessage(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_ephemeral_message, adapter) {

    private val highlightedBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val helpIcon: ImageView = itemView.findViewById(R.id.chat_list_adapter_item_icon_help)
    private val dismiss: TextView = itemView.findViewById(R.id.chat_list_adapter_item_text_dismiss)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val message = (data as EphemeralMessageEntry).message
        configureCellHighlight(message, highlightedBackground, gutterBackground)

        helpIcon.setOnClickListener {
            UriHandler.handle(helpIcon.context, EPHEMERAL_MESSAGES_HELP_ARTICLE)
        }

        // The footer covers exactly one message, so both plurals are always resolved for a
        // quantity of one; they exist as plurals because the desktop client groups them.
        val resources = dismiss.resources
        dismiss.movementMethod = LinkMovementMethod.getInstance()
        dismiss.text = ChatRowText.format(
            dismiss.context,
            R.string.only_you_can_see_and_delete_these,
            mapOf(
                "count" to resources.getQuantityString(R.plurals.only_you_can_see_and_delete_these_count, 1),
                "countMessages" to resources.getQuantityString(
                    R.plurals.only_you_can_see_and_delete_these_countMessages,
                    1,
                ),
            ),
            mapOf(
                "handleDelete" to ChatRowText.Hook(
                    spans = listOf(
                        ForegroundColorSpan(ContextCompat.getColor(itemView.context, R.color.brand)),
                    ),
                    onClick = { adapter.eventHandler.onDismissClicked(message) },
                ),
            ),
        )
    }

    private companion object {
        /** "Why can only I see this message?" in the support centre. */
        private const val EPHEMERAL_MESSAGES_HELP_ARTICLE =
            "https://support.discord.com/hc/en-us/articles/1500000580222"
    }
}
