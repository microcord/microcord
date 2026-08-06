package com.discord.api.user

import java.io.Serializable

data class PrimaryGuild(
    val identityEnabled: Boolean?,
    val identityGuildId: Long?,
    val tag: String?,
    val badge: String?,
) : Serializable
