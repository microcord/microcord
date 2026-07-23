package com.discord.api.presence

import com.discord.api.activity.Activity
import com.discord.api.user.User

data class Presence(
    val status: ClientStatus,
    val activities: List<Activity>? = null,
    val clientStatus: ClientStatuses? = null,
    val user: User? = null,
    val userId: Long? = null,
    val guildId: Long? = null,
)
