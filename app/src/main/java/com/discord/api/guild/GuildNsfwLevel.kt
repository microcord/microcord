package com.discord.api.guild

enum class GuildNsfwLevel(val apiValue: Int) {
    DEFAULT(0),
    EXPLICIT(1),
    SAFE(2),
    AGE_RESTRICTED(3),
    ;

    companion object {
        fun fromApiValue(value: Int) = entries.firstOrNull { it.apiValue == value } ?: DEFAULT
    }
}
