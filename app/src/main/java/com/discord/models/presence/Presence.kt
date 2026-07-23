package com.discord.models.presence

import com.discord.api.activity.Activity
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses

data class Presence(
    val status: ClientStatus,
    val clientStatuses: ClientStatuses?,
    val activities: List<Activity>?,
)
