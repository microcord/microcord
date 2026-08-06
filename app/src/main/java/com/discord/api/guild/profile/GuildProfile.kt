package com.discord.api.guild.profile

data class GuildProfile(
    val id: Long,
    val name: String,
    val iconHash: String?,
    val memberCount: Int,
    val onlineCount: Int,
    val description: String?,
    val brandColorPrimary: String?,
    val gameApplicationIds: List<Long>,
    val gameActivity: Map<Long, GameActivity>,
    val tag: String?,
    val badge: Int,
    val badgeColorPrimary: String,
    val badgeColorSecondary: String,
    val badgeHash: String,
    val traits: List<Trait>,
    val features: List<String>,
    val visibility: Int,
    val customBannerHash: String?,
    val premiumSubscriptionCount: Int,
    val premiumTier: Int,
) {
    data class GameActivity(
        val activityLevel: Int,
        val activityScore: Int,
    )

    data class Trait(
        val emojiId: Long?,
        val emojiName: String?,
        val emojiAnimated: Boolean,
        val label: String,
        val position: Int,
    )
}
