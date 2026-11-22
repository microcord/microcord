package com.discord.api.role

import java.io.Serializable

/**
 * A role's `colors` (Enhanced Role Styles, newer than 126.21): a gradient when [secondaryColor] is
 * set, and the "holographic" style when [tertiaryColor] is too. [GuildRole.color] still carries
 * the primary colour for clients that ignore this.
 */
data class GuildRoleColors(
    val primaryColor: Int,
    val secondaryColor: Int?,
    val tertiaryColor: Int?,
) : Serializable
