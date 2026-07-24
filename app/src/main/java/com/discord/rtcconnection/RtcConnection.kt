package com.discord.rtcconnection

class RtcConnection private constructor() {
    data class Metadata(
        val rtcConnectionId: String,
        val mediaSessionId: String?,
        val channelId: Long?,
        val guildId: Long?,
        val streamKey: String?,
    )
}
