package com.discord.widgets.channels.list.items

import com.discord.api.channel.Channel

/**
 * Audience total appended under a stage channel. Keyed by type alone, matching the reference —
 * only one stage channel can be expanded at a time, so the collision is not reachable.
 */
data class ChannelListItemStageAudienceCount(
    val channel: Channel,
    val audienceSize: Int,
) : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_STAGE_AUDIENCE_COUNT
}
