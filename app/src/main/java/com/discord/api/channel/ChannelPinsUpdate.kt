package com.discord.api.channel

import com.discord.api.utcdatetime.UtcDateTime

data class ChannelPinsUpdate(
    val channelId: Long,
    val guildId: Long?,
    val lastPinTimestamp: UtcDateTime?,
)
