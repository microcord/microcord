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
    val id: String? = null,
    val parentApplicationId: Long? = null,
    /** Which of [name], [state] or [details] the status line shows. */
    val statusDisplayType: StatusDisplayType? = null,
    val detailsUrl: String? = null,
    val stateUrl: String? = null,
    val secrets: ActivitySecrets? = null,
)
