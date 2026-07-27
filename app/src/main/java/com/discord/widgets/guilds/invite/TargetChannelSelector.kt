package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType

/**
 * Picks the channel an invite will be generated for.
 *
 * The explicitly selected channel wins; otherwise the guild's first invitable channel in
 * name-and-type order does, which is the same channel the sidebar would land on. `null` only when
 * the user can invite to nothing in the guild.
 */
class TargetChannelSelector {

    fun getTargetChannel(invitableChannels: Map<Long, Channel>, channelId: Long?): Channel? =
        invitableChannels[channelId]
            ?: invitableChannels.values.sortedWith(Channel.getSortByNameAndType()).firstOrNull()
}
