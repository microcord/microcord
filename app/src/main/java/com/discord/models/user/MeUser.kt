package com.discord.models.user

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance

data class MeUser(
    override val id: Long,
    override val username: String,
    override val avatar: String?,
    override val banner: String?,
    override val isBot: Boolean,
    override val isSystemUser: Boolean,
    override val discriminator: Int,
    override val premiumTier: PremiumTier,
    val email: String?,
    val mfaEnabled: Boolean,
    val isVerified: Boolean,
    val token: String?,
    override val flags: Int,
    override val publicFlags: Int,
    val phoneNumber: String?,
    val nsfwAllowance: NsfwAllowance,
    override val bio: String?,
    override val bannerColor: String?,
) : User {
    val hasBirthday = nsfwAllowance != NsfwAllowance.UNKNOWN
    override fun toString() =
        "MeUser(id=$id, username=$username, avatar=$avatar, banner=$banner, isBot=$isBot, " +
            "isSystemUser=$isSystemUser, discriminator=$discriminator, premiumTier=$premiumTier, " +
            "email=<redacted>, isVerified=$isVerified, mfaEnabled=$mfaEnabled, token=<redacted>, " +
            "flags=$flags, publicFlags=$publicFlags, phoneNumber=<redacted>, nsfwAllowance=$nsfwAllowance, " +
            "bio=$bio, bannerColor=$bannerColor, hasBirthday=$hasBirthday)"
}
