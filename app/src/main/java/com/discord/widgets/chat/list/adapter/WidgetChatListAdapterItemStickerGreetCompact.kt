package com.discord.widgets.chat.list.adapter

import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StickerGreetCompactEntry
import android.view.View
import rx.Subscription

/**
 * The compact greet prompt: the same "wave to someone" affordance as
 * [WidgetChatListAdapterItemStickerGreet], but shrunk to a single pill so it can sit above a DM that
 * already has a few messages instead of filling the empty screen.
 *
 * The whole pill is the button here — the sticker and its label are inside it — which is why the
 * click listener goes on the container rather than on a dedicated button view.
 */
class WidgetChatListAdapterItemStickerGreetCompact(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_sticker_greet_compact, adapter) {

    private val stickerView: StickerView =
        itemView.findViewById(R.id.chat_list_adapter_item_sticker_greet)
    private val label: TextView = itemView.findViewById(R.id.chat_list_adapter_item_sticker_greet_text)
    private val sendButton: View = itemView.findViewById(R.id.send_sticker_greet_button)

    override val subscription: Subscription? get() = stickerView.getSubscription()

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StickerGreetCompactEntry
        stickerView.setSticker(entry.sticker)
        label.text = label.i18nFormat(R.string.wave_to, "username" to entry.channelName)
        sendButton.setOnClickListener {
            adapter.eventHandler.onSendGreetMessageClicked(
                entry.channelId,
                entry.channelType,
                entry.sticker,
            )
        }
    }
}
