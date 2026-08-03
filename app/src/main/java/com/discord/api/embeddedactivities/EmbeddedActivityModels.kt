package com.discord.api.embeddedactivities

import com.discord.api.guildmember.GuildMember

data class EmbeddedActivityPlatformConfig(
    val labelType: Int,
    val labelFrom: String? = null,
    val labelUntil: String? = null,
    val releasePhase: String,
    val omitBadgeFromSurfaces: List<String> = emptyList(),
)

data class EmbeddedActivityConfig(
    val applicationId: Long? = null,
    val activityPreviewVideoAssetId: Long?,
    val supportedPlatforms: List<String>,
    val defaultOrientationLockState: Int,
    val tabletDefaultOrientationLockState: Int,
    val requiresAgeGate: Boolean,
    val legacyResponsiveAspectRatio: Boolean,
    val premiumTierRequirement: Int?,
    val freePeriodStartsAt: String?,
    val freePeriodEndsAt: String?,
    val clientPlatformConfig: Map<String, EmbeddedActivityPlatformConfig>,
    val shelfRank: Int,
    val hasCspException: Boolean,
    val displaysAdvertisements: Boolean,
    val supportedLocales: List<String> = emptyList(),
    val blockedLocales: List<String> = emptyList(),
)

data class EmbeddedActivityShelfApplication(
    val id: Long,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val coverImage: String? = null,
    val splash: String? = null,
    val flags: Long? = null,
    val flagsNew: String? = null,
    val maxParticipants: Int? = null,
    val embeddedActivityConfig: EmbeddedActivityConfig? = null,
)

data class EmbeddedActivityAssetMetadata(
    val contentType: String,
    val height: Int,
    val width: Int,
    val isAnimated: Boolean,
)

data class EmbeddedActivityAsset(
    val assetId: Long? = null,
    val assetType: String? = null,
    val key: String? = null,
    val metadata: EmbeddedActivityAssetMetadata? = null,
    val updatedAt: String? = null,
    val visibility: String? = null,
    val id: String? = null,
    val type: Int? = null,
    val name: String? = null,
)

data class EmbeddedActivityShelf(
    val activities: List<EmbeddedActivityConfig>,
    val applications: List<EmbeddedActivityShelfApplication>,
    val assets: Map<Long, List<EmbeddedActivityAsset>>,
)

data class EmbeddedActivityLocation(
    val id: String,
    val kind: String,
    val channelId: Long,
    val guildId: Long? = null,
)

data class EmbeddedActivityParticipant(
    val userId: Long,
    val member: GuildMember? = null,
    val sessionId: String,
    val nonce: String? = null,
)

data class EmbeddedActivityInstance(
    val applicationId: Long,
    val compositeInstanceId: String,
    val instanceId: Long,
    val launchId: Long,
    val location: EmbeddedActivityLocation,
    val participants: List<EmbeddedActivityParticipant>,
)

data class EmbeddedActivityLaunchBody(
    val sessionId: String,
    val guildId: Long? = null,
)

data class EmbeddedActivitySessionBody(val sessionId: String)

data class EmbeddedActivityProxyTicketRequest(val channelId: Long? = null)

data class EmbeddedActivityProxyTicket(val ticket: String) {
    override fun toString() = "EmbeddedActivityProxyTicket(ticket=<redacted>)"
}

data class EmbeddedActivityTrustedLink(val trusted: Boolean)

data class EmbeddedActivityDiscordConfig(
    val apiEndpoint: String,
    val environment: String,
    val cdnHost: String,
)

object EmbeddedActivityPlatform {
    const val MOBILE = "mobile"
    const val ANDROID = "android"
}

object EmbeddedActivityLocationKind {
    const val GUILD_CHANNEL = "gc"
    const val GUILD_CHANNEL_MESSAGE = "gcm"
    const val PRIVATE_CHANNEL = "pc"
    const val PRIVATE_CHANNEL_MESSAGE = "pcm"
    const val PARTY = "party"
}
