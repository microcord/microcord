package com.discord.models.user

import com.discord.api.premium.PremiumTier
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
    val isBot: Boolean
    val premiumTier: PremiumTier
}
