package com.discord.models.user

import com.discord.api.premium.PremiumTier
import com.discord.nullserializable.NullSerializable

data class CoreUser(
    override val id: Long,
    override val username: String = "",
    override val avatar: String? = null,
    override val banner: String? = null,
    override val isBot: Boolean = false,
    override val isSystemUser: Boolean = false,
    override val discriminator: Int = 0,
    override val premiumTier: PremiumTier = PremiumTier.NONE,
    override val flags: Int = 0,
    override val publicFlags: Int = 0,
    override val bio: String? = null,
    override val bannerColor: String? = null,
) : User {
    constructor(user: com.discord.api.user.User) : this(
        user.id,
        user.username,
        user.avatar?.value,
        user.banner?.value,
        user.bot ?: false,
        user.system ?: false,
        user.discriminator.toIntOrNull() ?: 0,
        user.premiumType ?: PremiumTier.NONE,
        user.flags ?: 0,
        user.publicFlags ?: 0,
        user.bio?.value,
        user.bannerColor?.value,
    )

    companion object {
        fun merge(oldUser: CoreUser, newUser: com.discord.api.user.User) = CoreUser(
            id = newUser.id,
            username = newUser.username,
            avatar = mergeNullSerializable(oldUser.avatar, newUser.avatar),
            banner = mergeNullSerializable(oldUser.banner, newUser.banner),
            isBot = newUser.bot ?: oldUser.isBot,
            isSystemUser = newUser.system ?: oldUser.isSystemUser,
            discriminator = newUser.discriminator.toIntOrNull() ?: oldUser.discriminator,
            premiumTier = newUser.premiumType ?: oldUser.premiumTier,
            flags = newUser.flags ?: oldUser.flags,
            publicFlags = newUser.publicFlags ?: oldUser.publicFlags,
            bio = mergeNullSerializable(oldUser.bio, newUser.bio),
            bannerColor = mergeNullSerializable(oldUser.bannerColor, newUser.bannerColor),
        )

        private fun <T> mergeNullSerializable(old: T?, new: NullSerializable<T>?): T? = when (new) {
            null -> old
            is NullSerializable.Null -> null
            is NullSerializable.Value -> new.value
        }
    }
}
