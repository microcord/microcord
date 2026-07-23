package com.discord.api.activity

data class Activity(
    val name: String,
    val type: ActivityType,
    val url: String?,
    val createdAt: Long,
    val timestamps: ActivityTimestamps?,
    val applicationId: Long?,
    val details: String?,
    val state: String?,
    val emoji: ActivityEmoji?,
    val party: ActivityParty?,
    val assets: ActivityAssets?,
    val flags: Int?,
    val syncId: String?,
    val sessionId: String?,
    val metadata: ActivityMetadata?,
    val platform: ActivityPlatform?,
    val supportedPlatforms: List<ActivityPlatform>?,
    val buttons: List<String>?,
)
