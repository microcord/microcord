package com.discord.rpc

import android.graphics.Color
import androidx.annotation.ColorInt
import com.discord.api.premium.PremiumTier
import com.discord.api.user.AvatarDecorationData
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import org.json.JSONArray
import org.json.JSONObject

/** `avatar_decoration_data` keys are camelCase on the wire even though everything else is snake_case. */
fun transformUser(user: User): JSONObject = JSONObject()
    .put("id", user.id.toString())
    .put("username", user.username)
    .put("discriminator", user.discriminator.toString())
    .put("global_name", user.globalName ?: JSONObject.NULL)
    .put("avatar", user.avatar ?: JSONObject.NULL)
    .put("avatar_decoration_data", transformAvatarDecoration(user.avatarDecoration))
    .put("bot", user.isBot)
    .put("flags", user.publicFlags)
    .put("premium_type", premiumType(user.premiumTier))

/** `pronouns` has no counterpart in the member model, so it goes out empty. */
fun transformGuildMember(member: GuildMember): JSONObject = JSONObject()
    .put("user_id", member.userId.toString())
    .put("nick", member.nick ?: JSONObject.NULL)
    .put("guild_id", member.guildId.toString())
    .put("avatar", member.avatarHash ?: JSONObject.NULL)
    .put("avatar_decoration_data", transformAvatarDecoration(member.avatarDecoration))
    .put("banner", member.bannerHash ?: JSONObject.NULL)
    .put("bio", member.bio.orEmpty())
    .put("pronouns", "")
    .put("color_string", colorString(member.color) ?: JSONObject.NULL)

fun transformGuild(guild: Guild): JSONObject = JSONObject()
    .put("id", guild.id.toString())
    .put("name", guild.name)
    .put("icon_url", guildIconUrl(guild) ?: JSONObject.NULL)

/** Optional keys are omitted rather than nulled, matching the reference's `undefined`. */
private fun transformAvatarDecoration(decoration: AvatarDecorationData?): Any {
    if (decoration == null) return JSONObject.NULL
    return JSONObject()
        .put("skuId", decoration.skuId.toString())
        .apply {
            if (decoration.asset.isNotEmpty()) put("asset", decoration.asset)
            decoration.expiresAt?.let { put("expiresAt", it) }
        }
}

/** The member model folds "no role colour" into black; the wire reports it as absent. */
private fun colorString(@ColorInt color: Int): String? =
    if (color == Color.BLACK || color == 0) null else String.format("#%06X", color and 0xFFFFFF)

private fun premiumType(tier: PremiumTier): Int = when (tier) {
    PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY -> 0
    PremiumTier.TIER_1 -> 1
    PremiumTier.TIER_2 -> 2
    PremiumTier.TIER_0 -> 3
    PremiumTier.NONE -> 0
}

private fun guildIconUrl(guild: Guild): String? = guild.icon?.let {
    "https://cdn.discordapp.com/icons/${guild.id}/$it.${if (it.startsWith("a_")) "gif" else "webp"}?size=128"
}

fun jsonArrayOf(values: List<JSONObject>): JSONArray = JSONArray().apply { values.forEach(::put) }
