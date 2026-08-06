package com.discord.api.user

import com.discord.api.guildmember.GuildMember
import com.discord.api.premium.PremiumTier
import com.discord.nullserializable.NullSerializable
import java.io.Serializable

data class User(
    val id: Long,
    val username: String,
    val avatar: NullSerializable<String>? = null,
    val banner: NullSerializable<String>? = null,
    val discriminator: String = "0000",
    val publicFlags: Int? = null,
    val flags: Int? = null,
    val bot: Boolean? = null,
    val system: Boolean? = null,
    val token: String? = null,
    val email: String? = null,
    val verified: Boolean? = null,
    val locale: String? = null,
    val nsfwAllowed: NsfwAllowance? = null,
    val mfaEnabled: Boolean? = null,
    val phone: Phone? = null,
    val analyticsToken: String? = null,
    val premiumType: PremiumTier? = null,
    val approximateGuildCount: Int? = null,
    val member: GuildMember? = null,
    val bio: NullSerializable<String>? = null,
    val bannerColor: NullSerializable<String>? = null,
    val globalName: String? = null,
    val collectibles: NullSerializable<Collectibles>? = null,
    val avatarDecorationData: NullSerializable<AvatarDecorationData>? = null,
    val storeCountry: UserStoreCountry? = null,
    val primaryGuild: PrimaryGuild? = null,
) : Serializable {
    override fun toString() =
        "User(id=$id, username=$username, avatar=$avatar, banner=$banner, discriminator=$discriminator, " +
            "publicFlags=$publicFlags, flags=$flags, bot=$bot, system=$system, token=<redacted>, " +
            "email=<redacted>, verified=$verified, locale=$locale, nsfwAllowed=$nsfwAllowed, " +
            "mfaEnabled=$mfaEnabled, phone=<redacted>, analyticsToken=<redacted>, premiumType=$premiumType, " +
            "approximateGuildCount=$approximateGuildCount, member=$member, bio=$bio, bannerColor=$bannerColor, " +
            "globalName=$globalName, collectibles=$collectibles, avatarDecorationData=$avatarDecorationData, " +
            "storeCountry=$storeCountry, primaryGuild=$primaryGuild)"
}
