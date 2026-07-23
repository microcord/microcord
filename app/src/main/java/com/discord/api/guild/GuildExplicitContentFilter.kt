package com.discord.api.guild

enum class GuildExplicitContentFilter(val apiValue: Int) {
    NONE(0), SOME(1), ALL(2),
}
