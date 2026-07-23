package com.discord.api.voice.state

import com.discord.api.guildmember.GuildMember
import com.discord.api.utcdatetime.UtcDateTime

data class VoiceState(
    val userId: Long,
    val suppress: Boolean,
    val sessionId: String?,
    val selfMute: Boolean,
    val selfDeaf: Boolean,
    val selfVideo: Boolean,
    val selfStream: Boolean,
    val mute: Boolean,
    val guildId: Long,
    val deaf: Boolean,
    val member: GuildMember?,
    val requestToSpeakTimestamp: UtcDateTime?,
    val channelId: Long?,
)
