package com.discord.models.domain

import com.google.gson.annotations.SerializedName

data class ModelReadState(
    @SerializedName(value = "id", alternate = ["channel_id"])
    val channelId: Long,
    @SerializedName(value = "last_message_id", alternate = ["message_id"])
    val lastMessageId: Long,
    @SerializedName("mention_count")
    val mentionCount: Int,
    val version: Int,
) {
    constructor(
        id: Long,
        channelId: Long,
        lastMessageId: Long = 0,
        messageId: Long = 0,
        mentionCount: Int,
        version: Int,
    ) : this(
        channelId = channelId.takeUnless { it == 0L } ?: id,
        lastMessageId = lastMessageId.takeUnless { it == 0L } ?: messageId,
        mentionCount = mentionCount,
        version = version,
    )

    val isMentioned: Boolean get() = mentionCount > 0
}

data class ModelChannelUnreadUpdate(
    @SerializedName("channel_unread_updates")
    val channelReadStates: List<ModelReadState>,
)
