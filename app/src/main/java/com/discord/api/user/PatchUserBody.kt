package com.discord.api.user

import com.discord.nullserializable.NullSerializable

/**
 * Body of `PATCH users/@me` for the account-wide identity fields.
 *
 * Bio, banner and accent colour are profile metadata and go to `users/@me/profile` instead; see
 * [PatchUserProfileBody].
 *
 * Each [NullSerializable] distinguishes "leave unchanged" (absent) from "clear this" (explicit
 * null on the wire).
 */
data class PatchUserBody(
    val avatar: NullSerializable<String>? = null,
    val globalName: NullSerializable<String>? = null,
    val avatarDecorationSkuId: NullSerializable<String>? = null,
    val nameplateSkuId: NullSerializable<String>? = null,
    // A display name style is written as three flat fields even though it is read back nested
    // under `display_name_styles`; all three move together, and all three null to clear it.
    val displayNameFontId: NullSerializable<Int>? = null,
    val displayNameEffectId: NullSerializable<Int>? = null,
    val displayNameColors: NullSerializable<List<Int>>? = null,
)
