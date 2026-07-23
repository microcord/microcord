package com.discord.api.activity

enum class ActivityType(val apiInt: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    CUSTOM_STATUS(4),
    COMPETING(5),
    UNKNOWN(-1),
}
