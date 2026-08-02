package com.discord.models.user

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.Phone

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
    override val globalName: String? = null,
) : User {
    constructor(user: com.discord.api.user.User) : this(
        id = user.id,
        username = user.username,
        avatar = user.avatar?.value,
        banner = user.banner?.value,
        isBot = user.bot ?: false,
        isSystemUser = user.system ?: false,
        discriminator = user.discriminator.toIntOrNull() ?: 0,
        premiumTier = user.premiumType ?: PremiumTier.NONE,
        email = user.email,
        mfaEnabled = user.mfaEnabled ?: false,
        isVerified = user.verified ?: false,
        token = user.token,
        flags = user.flags ?: 0,
        publicFlags = user.publicFlags ?: 0,
        phoneNumber = (user.phone as? Phone.PhoneNumber)?.number,
        nsfwAllowance = user.nsfwAllowed ?: NsfwAllowance.UNKNOWN,
        bio = user.bio?.value,
        bannerColor = user.bannerColor?.value,
        globalName = user.globalName,
    )

    val hasBirthday = nsfwAllowance != NsfwAllowance.UNKNOWN
    override fun toString() =
        "MeUser(id=$id, username=$username, avatar=$avatar, banner=$banner, isBot=$isBot, " +
            "isSystemUser=$isSystemUser, discriminator=$discriminator, premiumTier=$premiumTier, " +
            "email=<redacted>, isVerified=$isVerified, mfaEnabled=$mfaEnabled, token=<redacted>, " +
            "flags=$flags, publicFlags=$publicFlags, phoneNumber=<redacted>, nsfwAllowance=$nsfwAllowance, " +
            "bio=$bio, bannerColor=$bannerColor, globalName=$globalName, hasBirthday=$hasBirthday)"
}
