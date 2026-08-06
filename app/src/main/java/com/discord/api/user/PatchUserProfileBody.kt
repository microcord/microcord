package com.discord.api.user

import com.discord.nullserializable.NullSerializable

/**
 * Body of `PATCH users/@me/profile` and `PATCH guilds/{guildId}/profile/@me`.
 *
 * [bio] and [pronouns] are plain strings: the server treats them as non-nullable and an empty
 * string clears them. [banner] and [accentColor] are genuinely nullable, so clearing those needs an
 * explicit `null` on the wire rather than an omitted key.
 *
 * A guild profile has no accent colour; the reference never sends one to the guild route.
 */
data class PatchUserProfileBody(
    val banner: NullSerializable<String>? = null,
    val bio: String? = null,
    val pronouns: String? = null,
    val accentColor: NullSerializable<Int>? = null,
    val themeColors: NullSerializable<List<Int>>? = null,
    /**
     * Every collectible the profile should end up with, by SKU. The server replaces the whole set,
     * so an unchanged frame has to be sent back alongside a changed effect or it is unequipped.
     */
    val collectiblesSkuIds: List<String>? = null,
)
