package com.discord.api.user

import com.discord.nullserializable.NullSerializable

/**
 * Body of `PATCH users/@me` for the profile-editing fields.
 *
 * Each [NullSerializable] distinguishes "leave unchanged" (absent) from "clear this" (explicit
 * null on the wire).
 */
data class PatchUserBody(
    val avatar: NullSerializable<String>? = null,
    val banner: NullSerializable<String>? = null,
    val bannerColor: NullSerializable<String>? = null,
    val bio: NullSerializable<String>? = null,
)
