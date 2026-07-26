package com.discord.models.domain

import com.discord.api.user.User

/**
 * Payload of `GUILD_BAN_ADD` / `GUILD_BAN_REMOVE`, and the row type of the guild ban list.
 *
 * The gateway always sends `guild_id`. The REST ban-list response omits it because the caller knows
 * which guild it queried, so a REST-sourced row carries [NO_GUILD] and must be re-associated by the
 * caller.
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
