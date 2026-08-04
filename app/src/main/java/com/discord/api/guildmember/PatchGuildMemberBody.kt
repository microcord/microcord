package com.discord.api.guildmember

import com.discord.nullserializable.NullSerializable

/**
 * Body of `PATCH guilds/{guildId}/members/{userId}`.
 *
 * Only the member's identity lives here. The per-guild bio, banner and pronouns are profile
 * metadata and go to `guilds/{guildId}/profile/@me` instead; see
 * [com.discord.api.user.PatchUserProfileBody].
 *
 * Every field is optional; the [NullSerializable] wrapper distinguishes "leave unchanged" (absent)
 * from "clear this" (explicit null on the wire).
 */
data class PatchGuildMemberBody(
    val nick: String? = null,
    val avatar: NullSerializable<String>? = null,
)
