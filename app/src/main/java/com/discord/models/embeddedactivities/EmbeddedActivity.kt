package com.discord.models.embeddedactivities

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
    val userIds: List<Long>,
    val url: String,
    val guildId: Long,
) {
    companion object {
        fun fromApiEmbeddedActivity(
            embeddedActivity: com.discord.api.embeddedactivities.EmbeddedActivity,
            userIds: List<Long>,
            url: String,
            guildId: Long,
        ) = EmbeddedActivity(
            embeddedActivity.applicationId,
            embeddedActivity.assets,
            embeddedActivity.createdAt,
            embeddedActivity.details,
            embeddedActivity.name,
            embeddedActivity.secrets,
            embeddedActivity.state,
            embeddedActivity.timestamps,
            embeddedActivity.type,
            userIds,
            url,
            guildId,
        )
    }
}
