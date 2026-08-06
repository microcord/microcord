package com.discord.widgets.chat.list.adapter

import android.view.View
import com.discord.R
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.widgets.chat.list.ChatAttachmentView
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry

/**
 * A standalone attachment row.
 *
 * A message's own uploads render inside its message row; this exists for the surfaces that hand an
 * attachment to the list on its own.
 */
class WidgetChatListAdapterItemAttachment(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_attachment, adapter),
    FragmentLifecycleListener {

    private val highlightedBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val attachmentView: ChatAttachmentView =
        itemView.findViewById(R.id.chat_list_item_attachment_view)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as AttachmentEntry
        configureCellHighlight(entry.message, highlightedBackground, gutterBackground, entry.isPendingReply)

        itemView.setOnLongClickListenerConsumeClick {
            adapter.eventHandler.onMessageLongClicked(
                entry.message,
                entry.message.content.orEmpty(),
                entry.isThreadStarterMessage,
            )
        }
        itemView.setOnClickListener {
            adapter.eventHandler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
        }

        attachmentView.configure(
            entry = entry,
            isSpoilerClickAllowed = adapter.data.isSpoilerClickAllowed,
            onSpoilerBlocked = {
                adapter.eventHandler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
            },
            onQuickDownload = adapter.eventHandler::onQuickDownloadClicked,
        )
    }

    override fun onPause() = attachmentView.inlineMedia.onPause()

    override fun onResume() = attachmentView.inlineMedia.onResume()
}
