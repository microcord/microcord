package com.discord.widgets.chat.list.adapter

import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.utilities.rx.appSubscribe
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import com.discord.widgets.stickers.StickerStores
import rx.Observable
import rx.Subscription

/**
 * A sticker sent with a message.
 *
 * [StickerView] owns everything about how the sticker is drawn — downloading the Lottie/APNG asset,
 * honouring the account's sticker-animation setting (always / on long press / never), and exposing
 * the download subscription this holder hands back to the adapter so it is cancelled when the row is
 * recycled. Passing no local animation setting is deliberate: the view then follows the global
 * setting store, which is what a sticker inside a message should do.
 */
class WidgetChatListAdapterItemSticker(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_sticker, adapter) {

    private val stickerView: StickerView = itemView.findViewById(R.id.chat_list_adapter_item_sticker)

    override val subscription: Subscription? get() = stickerView.getSubscription()

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StickerEntry
        Observable.just(resolveSticker(entry.sticker.getStickerId()))
            .take(1)
            .appSubscribe(WidgetChatListAdapterItemSticker::class.java) { hydratedSticker ->
                val sticker = hydratedSticker ?: entry.sticker
                stickerView.setSticker(sticker)
                stickerView.setOnClickListener { adapter.onStickerClicked(entry.message, sticker) }
            }
    }
}

internal fun resolveSticker(
    stickerId: Long,
    standardSticker: (Long) -> Sticker? = { StickerStores.stickers.getStickers()[it] },
    guildSticker: (Long) -> Sticker? = StickerStores.guildStickers::getGuildSticker,
): Sticker? = standardSticker(stickerId) ?: guildSticker(stickerId)
