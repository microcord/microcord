package com.discord.widgets.chat.list.entries

/** A Nitro gift link embedded in a message. */
data class GiftEntry(
    val userId: Long,
    val messageId: Long,
    val channelId: Long,
    val giftCode: String,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GIFT
    override val key: String = "${ChatListEntry.GIFT} -- $messageId -- $giftCode"
}
