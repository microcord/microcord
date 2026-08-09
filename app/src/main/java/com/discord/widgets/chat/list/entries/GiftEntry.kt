package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
/** A Nitro gift link embedded in a message. */
data class GiftEntry(
    val userId: Long,
    val messageId: Long,
    val channelId: Long,
    val giftCode: String,
    val message: Message,
) : ChatListEntry() {

    override val highlightMessage: Message get() = message
    override val type: Int = ChatListEntry.GIFT
    override val key: String = "${ChatListEntry.GIFT} -- $messageId -- $giftCode"
}
