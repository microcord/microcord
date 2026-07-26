package com.discord.models.domain

import com.discord.api.user.User

/** A Twitch/YouTube integration attached to a guild. */
data class ModelGuildIntegration(
    val id: Long,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val syncing: Boolean,
    val roleId: Long,
    val enableEmoticons: Boolean,
    val expireBehavior: Int,
    val expireGracePeriod: Int,
    val user: User?,
    val account: IntegrationAccount?,
    val syncedAt: String?,
    val subscriberCount: Int,
) {
    /** The provider's own account identity; [id] is a provider handle, not a Discord snowflake. */
    data class IntegrationAccount(val id: String, val name: String)

    /** Payload of `GUILD_INTEGRATIONS_UPDATE`: a nudge to refetch the guild's integrations. */
    data class Update(val guildId: Long)

    val displayName: String
        get() = if (type == TYPE_TWITCH) "twitch.tv/$name" else name

    companion object {
        const val TYPE_TWITCH = "twitch"
        const val TYPE_YOUTUBE = "youtube"
        val SUPPORTED_TYPES = listOf(TYPE_TWITCH, TYPE_YOUTUBE)
    }
}
