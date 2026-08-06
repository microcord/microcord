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
    val avatarDecorationSkuId: NullSerializable<String>? = null,
    val collectibles: NullSerializable<PatchCollectibles>? = null,
)

/**
 * Per-guild collectibles. The nameplate is nested here, unlike the account-wide `nameplate_sku_id`
 * on [com.discord.api.user.PatchUserBody] — the two scopes genuinely disagree on shape.
 *
 * `{"nameplate": null}` drops the guild override so the global nameplate applies again.
 */
data class PatchCollectibles(
    val nameplate: NullSerializable<PatchNameplate>? = null,
)

data class PatchNameplate(val skuId: String)
