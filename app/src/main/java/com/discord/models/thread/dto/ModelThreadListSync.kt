package com.discord.models.thread.dto

import com.discord.api.channel.Channel
import com.discord.api.thread.ThreadMember
import com.discord.models.message.Message

data class ModelThreadListSync(
    val guildId: Long,
    val threads: List<Channel>,
    val members: List<ThreadMember>? = null,
    val mostRecentMessages: List<Message>? = null,
)
