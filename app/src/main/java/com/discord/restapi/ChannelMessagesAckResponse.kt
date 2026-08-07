package com.discord.restapi

/** Ack response. Carries the token the next ack must echo back. */
data class ChannelMessagesAckResponse(val token: String? = null)
