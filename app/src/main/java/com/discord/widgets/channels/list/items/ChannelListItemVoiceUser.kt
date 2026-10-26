package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel
import com.discord.api.voice.state.VoiceState
import com.discord.models.member.GuildMember
import com.discord.models.user.User

data class ChannelListItemVoiceUser(
    val channel: Channel,
    val voiceState: VoiceState,
    val user: User,
    val computed: GuildMember,
    val isApplicationStreaming: Boolean,
    val hasChannelConnectPermission: Boolean,
    /** The current user, connected from another of their clients rather than this one. */
    val isOtherSession: Boolean = false,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_CHANNEL_VOICE_USER
    override val key: String = "$type${voiceState.channelId}${voiceState.userId}"
}
