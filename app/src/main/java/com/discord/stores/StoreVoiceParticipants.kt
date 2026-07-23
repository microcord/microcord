package com.discord.stores

import com.discord.api.voice.state.VoiceState
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.streams.StreamContext

object StoreVoiceParticipants {
    interface VoiceUser {
        val user: User
        val voiceState: VoiceState?
        val guildMember: GuildMember?
        val displayName: String
        val isSpeaking: Boolean
        val isRinging: Boolean
        val isConnected: Boolean
        val callStreamId: Int? get() = null
        val applicationStreamId: Int? get() = null
        val streamContext: StreamContext? get() = null
        val isMuted: Boolean get() = voiceState?.let { it.selfMute || it.mute } == true
        val isDeafened: Boolean get() = voiceState?.let { it.selfDeaf || it.deaf } == true
    }
}
