package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance

data class ChannelListItemStageVoiceChannel(
    override val channel: Channel,
    val selected: Boolean,
    val permission: Long?,
    val isLocked: Boolean,
    override val numUsersConnected: Int,
    val stageInstance: StageInstance?,
    val hasSpeakers: Boolean,
    val isGuildRoleSubscriptionLockedChannel: Boolean,
    val isGuildRoleSubscriptionChannel: Boolean,
) : ChannelListItem, ChannelListVocalItem {
    override val type: Int = ChannelListItem.TYPE_CHANNEL_STAGE_VOICE
    override val key: String = "$type${channel.id}"

    /** A live stage instance is what turns the row into an "event happening now" row. */
    val hasActiveEvent: Boolean get() = stageInstance != null
}
