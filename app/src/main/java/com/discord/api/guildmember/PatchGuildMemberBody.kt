package com.discord.api.guildmember

import com.discord.nullserializable.NullSerializable

/**
 * Body of `PATCH guilds/{guildId}/members/{userId}`.
 *
 * Every field is optional; the [NullSerializable] wrappers distinguish "leave unchanged" (absent)
 * from "clear this" (explicit null on the wire).
 */
data class PatchGuildMemberBody(
    val nick: String? = null,
    val avatar: NullSerializable<String>? = null,
    val banner: NullSerializable<String>? = null,
    val bio: String? = null,
)
