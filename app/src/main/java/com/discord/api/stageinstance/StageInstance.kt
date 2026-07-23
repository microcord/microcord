package com.discord.api.stageinstance

data class StageInstance(
    val id: Long,
    val guildId: Long,
    val channelId: Long,
    val topic: String,
    val privacyLevel: StageInstancePrivacyLevel,
    val inviteCode: String?,
)

enum class StageInstancePrivacyLevel(val apiValue: Int) {
    INVALID(0),
    PUBLIC(1),
    GUILD_ONLY(2),
}
