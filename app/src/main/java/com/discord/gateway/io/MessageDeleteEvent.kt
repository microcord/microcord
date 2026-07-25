package com.discord.gateway.io

import com.google.gson.annotations.SerializedName

data class MessageDeleteEvent(
    @SerializedName("channel_id") val channelId: Long,
    @SerializedName("guild_id") val guildId: Long? = null,
    val id: Long? = null,
    val ids: List<Long>? = null,
) {
    val messageIds: List<Long> get() = ids ?: listOfNotNull(id)
}
