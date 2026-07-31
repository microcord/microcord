package com.discord.widgets.chat.list.entries

import com.discord.api.sticker.Sticker

/** Full-size sticker greeting shown in an empty direct message. */
data class StickerGreetEntry(
    val sticker: Sticker,
    val channelId: Long,
    val channelName: String,
    val channelType: Int,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.STICKER_GREET
    override val key: String = "${ChatListEntry.STICKER_GREET} -- ${sticker.id}"
}
