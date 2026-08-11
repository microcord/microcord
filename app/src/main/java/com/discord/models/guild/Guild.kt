package com.discord.models.guild

import com.discord.api.emoji.GuildEmoji
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildHubType
import com.discord.api.guild.GuildTagProfile
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guild.GuildIncidentsData
import com.discord.api.guild.GuildNsfwLevel
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.guild.welcome.GuildWelcomeScreen
import com.discord.api.role.GuildRole
import com.discord.api.sticker.Sticker

data class Guild(
    val roles: List<GuildRole> = emptyList(),
    val emojis: List<GuildEmoji> = emptyList(),
    val stickers: List<Sticker> = emptyList(),
    val name: String = "",
    val description: String? = null,
    val defaultMessageNotifications: Int = 0,
    val id: Long = 0L,
    val region: String? = null,
    val ownerId: Long = 0L,
    val icon: String? = null,
    val verificationLevel: GuildVerificationLevel = GuildVerificationLevel.NONE,
    val explicitContentFilter: GuildExplicitContentFilter = GuildExplicitContentFilter.NONE,
    val unavailable: Boolean = false,
    val mfaLevel: Int = 0,
    val afkTimeout: Int = 0,
    val afkChannelId: Long? = null,
    val systemChannelId: Long? = null,
    val features: Set<GuildFeature> = emptySet(),
    val memberCount: Int = 0,
    val banner: String? = null,
    val splash: String? = null,
    val premiumTier: Int = 0,
    val premiumSubscriptionCount: Int = 0,
    val systemChannelFlags: Int = 0,
    val joinedAt: String? = null,
    val rulesChannelId: Long? = null,
    val publicUpdatesChannelId: Long? = null,
    val preferredLocale: String? = null,
    val welcomeScreen: GuildWelcomeScreen? = null,
    val maxVideoChannelUsers: GuildMaxVideoChannelUsers = GuildMaxVideoChannelUsers.Unlimited,
    val vanityUrlCode: String? = null,
    val approximatePresenceCount: Int = 0,
    val nsfw: Boolean = false,
    val hubType: GuildHubType? = null,
    val safetyAlertsChannelId: Long? = null,
    val premiumProgressBarEnabled: Boolean = false,
    val nsfwLevel: GuildNsfwLevel = GuildNsfwLevel.DEFAULT,
    val ownerConfiguredContentLevel: GuildNsfwLevel? = null,
    val incidentsData: GuildIncidentsData? = null,
    val officialMessageColor: Int? = null,
    /** The guild's server tag, when it has one. */
    val profile: GuildTagProfile? = null,
) {
    val shortName: String = toShortName(name)

    fun hasIcon() = !icon.isNullOrEmpty()

    companion object {
        /** Initials shown in place of an icon, also used by rows that have no full guild record. */
        fun toShortName(name: String): String = name.replace(Regex("'s "), " ")
            .replace(Regex("(\\w)(\\w*)"), "$1")
            .replace(Regex("\\s"), "")
    }
    fun hasFeature(feature: GuildFeature) = feature in features
    fun isOwner(userId: Long) = ownerId == userId
    fun isHub() = hasFeature(GuildFeature.HUB)
    fun canHaveAnimatedBanner() = hasFeature(GuildFeature.ANIMATED_BANNER)

    /*
     * Boosting is tierless: a guild unlocks a perk by having powerups applied to it, and the server
     * reports the result in `features`. `premiumTier` is now only the level shown on progress bars
     * and badges, so deriving entitlement from it grants perks the guild has not actually unlocked.
     */

    fun canHaveBanner() = hasFeature(GuildFeature.BANNER)
    fun canHaveSplash() = hasFeature(GuildFeature.INVITE_SPLASH)

    /** A guild that lost the perk keeps a vanity it already set, but can no longer edit it. */
    fun canHaveVanityURL() =
        hasFeature(GuildFeature.VANITY_URL) ||
            (hasFeature(GuildFeature.GUILD_WEB_PAGE_VANITY_URL) && !vanityUrlCode.isNullOrEmpty())
}
