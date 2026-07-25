package com.discord.api.message.call

import com.discord.api.utcdatetime.UtcDateTime

data class MessageCall(
    val participants: List<Long>,
    val endedTimestamp: UtcDateTime?,
)
