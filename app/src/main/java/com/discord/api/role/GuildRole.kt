package com.discord.api.role

import java.io.Serializable

data class GuildRole(
    val position: Int,
    val name: String,
    val id: Long,
    val color: Int,
    val hoist: Boolean,
    val permissions: Long,
    val mentionable: Boolean,
    val managed: Boolean,
    val icon: String?,
    val tags: GuildRoleTags?,
    val unicodeEmoji: String?,
) : Serializable {
    @Transient var version: Long = 0
}
