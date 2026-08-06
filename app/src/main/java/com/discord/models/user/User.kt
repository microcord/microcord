package com.discord.models.user

import com.discord.api.premium.PremiumTier
import com.discord.api.user.AvatarDecorationData
import com.discord.api.user.Collectibles
import java.io.Serializable

interface User : Serializable {
    val isSystemUser: Boolean
    val flags: Int
    val publicFlags: Int
    val avatar: String?
    val bio: String?
    val id: Long
    val bannerColor: String?
    val banner: String?
    val discriminator: Int
    val username: String
    val globalName: String? get() = null
    val collectibles: Collectibles? get() = null
    val avatarDecoration: AvatarDecorationData? get() = null
    val isBot: Boolean
    val premiumTier: PremiumTier

    /**
     * A webhook author. `discriminator` narrows to `Int` here, which cannot tell the webhook's
     * `0000` from a migrated account's `0`, so the distinction is carried separately.
     */
    val isWebhook: Boolean get() = false
}
