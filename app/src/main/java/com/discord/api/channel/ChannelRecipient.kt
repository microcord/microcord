package com.discord.api.channel

import com.discord.api.user.User

/** Payload of `CHANNEL_RECIPIENT_ADD` / `CHANNEL_RECIPIENT_REMOVE`. */
data class ChannelRecipient(
    val nick: String?,
    val channelId: Long,
    val user: User,
)
