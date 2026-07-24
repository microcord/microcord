package com.discord.utilities.voice

enum class VoiceChannelJoinability {
    CAN_JOIN,
    PERMISSIONS_MISSING,
    GUILD_VIDEO_AT_CAPACITY,
    CHANNEL_FULL,
    CHANNEL_DOES_NOT_EXIST,
}
