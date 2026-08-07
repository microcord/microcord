package com.discord.models.domain

import com.discord.api.notification.ReadStateFlags
import com.discord.api.notification.ReadStateTypes
import com.discord.api.utcdatetime.UtcDateTime
import com.google.gson.annotations.SerializedName

data class ModelReadState(
    @SerializedName(value = "id", alternate = ["channel_id"])
    val channelId: Long,
    /** Non-channel read states reuse this field as `last_acked_id`. */
    @SerializedName(value = "last_message_id", alternate = ["message_id", "last_acked_id"])
    val lastMessageId: Long,
    /** Non-channel read states report the same number as `badge_count`. */
    @SerializedName(value = "mention_count", alternate = ["badge_count"])
    val mentionCount: Int,
    val version: Int,
    val flags: Int = 0,
    /** Days since the Discord epoch on which the channel was last opened. */
    @SerializedName("last_viewed")
    val lastViewed: Int = 0,
    @SerializedName("last_pin_timestamp")
    val lastPinTimestamp: UtcDateTime? = null,
    @SerializedName("read_state_type")
    val readStateType: Int = ReadStateTypes.CHANNEL,
) {
    val isChannelReadState: Boolean get() = readStateType == ReadStateTypes.CHANNEL
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

    /** Whether this channel's mentions came only from an all-messages channel, not a real ping. */
    val isMentionLowImportance: Boolean
        get() = flags and ReadStateFlags.IS_MENTION_LOW_IMPORTANCE == ReadStateFlags.IS_MENTION_LOW_IMPORTANCE
}

data class ModelChannelUnreadUpdate(
    @SerializedName("channel_unread_updates")
    val channelReadStates: List<ModelReadState>,
)
