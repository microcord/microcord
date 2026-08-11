package com.discord.api.guild

import java.io.Serializable

/**
 * The `profile` object carried inline on a guild, holding its server tag.
 *
 * Distinct from [com.discord.api.guild.profile.GuildProfile], which is the far larger object
 * `GET guilds/{id}/profile` returns: that one describes the guild's discovery page and reports the
 * badge as an artwork kind plus colours, while this one is the two fields the gateway already sends
 * with every guild and is what decides whether a guild can lend its tag.
 */
data class GuildTagProfile(
    val tag: String? = null,
    /** CDN hash for `clan-badges/{guildId}/{badge}.png`, not a [com.discord.api.guild.profile.GuildProfile] badge kind. */
    val badge: String? = null,
) : Serializable
