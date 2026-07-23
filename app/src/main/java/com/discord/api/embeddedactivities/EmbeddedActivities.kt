package com.discord.api.embeddedactivities

import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivitySecrets
import com.discord.api.activity.ActivityTimestamps
import com.discord.api.activity.ActivityType

data class EmbeddedActivity(
    val applicationId: Long,
    val assets: ActivityAssets?,
    val createdAt: Long?,
    val details: String?,
    val name: String?,
    val secrets: ActivitySecrets?,
    val state: String?,
    val timestamps: ActivityTimestamps?,
    val type: ActivityType,
)

data class EmbeddedActivityInGuild(
    val channelId: Long,
    val embeddedActivity: EmbeddedActivity,
    val users: List<Long>,
)
