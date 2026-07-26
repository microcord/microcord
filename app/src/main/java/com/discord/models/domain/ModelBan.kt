package com.discord.models.domain

import com.discord.api.user.User

/**
 * Payload of `GUILD_BAN_ADD` / `GUILD_BAN_REMOVE`, and the row type of the guild ban list.
 *
 * [guildId] defaults to [NO_GUILD] because the REST ban-list response omits it — the caller knows
 * which guild it queried.
 */
data class ModelBan(
    val guildId: Long = NO_GUILD,
    val user: User,
    val reason: String? = null,
) {
    companion object {
        const val NO_GUILD = -1L
    }
}
