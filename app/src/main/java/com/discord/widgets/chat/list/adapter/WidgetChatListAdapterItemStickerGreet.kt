package com.discord.widgets.chat.list.adapter

import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StickerGreetEntry
import com.google.android.material.button.MaterialButton
import rx.Subscription

/**
 * The full-size "wave to someone" prompt shown in a DM that has no history at all.
 *
 * The sticker is rendered through [StickerView] with no local animation override, so it follows the
 * account's sticker-animation setting like any other sticker. Tapping the button sends the greeting
 * immediately — the adapter's host owns the send, this row only reports the intent.
 */
class WidgetChatListAdapterItemStickerGreet(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_sticker_greet, adapter) {

    private val stickerView: StickerView =
        itemView.findViewById(R.id.chat_list_adapter_item_sticker_greet)
    private val sendButton: MaterialButton = itemView.findViewById(R.id.send_sticker_greet_button)

    override val subscription: Subscription? get() = stickerView.getSubscription()

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StickerGreetEntry
        stickerView.setSticker(entry.sticker)
        sendButton.text = sendButton.i18nFormat(R.string.wave_to, "username" to entry.channelName)
        sendButton.setOnClickListener {
            adapter.eventHandler.onSendGreetMessageClicked(
                entry.channelId,
                entry.channelType,
                entry.sticker,
            )
        }
    }
}
