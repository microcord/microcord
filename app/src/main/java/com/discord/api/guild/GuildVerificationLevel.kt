package com.discord.api.guild

enum class GuildVerificationLevel(val apiValue: Int) {
    NONE(0), LOW(1), MEDIUM(2), HIGH(3), HIGHEST(4),
}
