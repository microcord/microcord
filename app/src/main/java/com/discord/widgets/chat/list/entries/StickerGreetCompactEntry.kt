package com.discord.widgets.chat.list.entries

import com.discord.api.sticker.Sticker

/** Compact sticker greeting shown when a direct message already has history. */
data class StickerGreetCompactEntry(
    val sticker: Sticker,
    val channelId: Long,
    val channelName: String,
    val channelType: Int,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.STICKER_GREET_COMPACT
    override val key: String = "${ChatListEntry.STICKER_GREET_COMPACT} -- ${sticker.id}"
}
