package com.discord.widgets.chat.list.entries

import com.discord.api.sticker.BaseSticker
import com.discord.models.message.Message

/** One sticker sent with a message. */
data class StickerEntry(val message: Message, val sticker: BaseSticker) : ChatListEntry() {
    override val type: Int = ChatListEntry.STICKER
    override val key: String =
        "${ChatListEntry.STICKER} -- ${message.id} -- ${sticker.getStickerId()}"
}
