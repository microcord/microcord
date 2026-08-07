package com.discord.utilities.channel

import com.discord.api.channel.Channel
import com.discord.api.channel.isSpoilerChannel
import com.discord.api.user.NsfwAllowance
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream

/**
 * Whether a channel's content is currently withheld from the viewer.
 *
 * Two independent gates: an age gate on an NSFW channel, and a spoiler gate on a channel someone
 * has marked as containing spoilers. Both are "not yet agreed" states rather than properties of the
 * channel, so agreeing re-enables everything that hangs off them — pins included.
 */
object ChannelGating {

    fun isChannelContentGated(
        channel: Channel?,
        me: MeUser? = StoreStream.getUsers().getMe(),
    ): Boolean {
        channel ?: return false
        if (!channel.nsfw) return false
        if (me?.nsfwAllowance == NsfwAllowance.DISALLOWED) return true
        return !StoreStream.getGuildsNsfw().isGuildNsfwGateAgreed(channel.guildId)
    }

    fun isChannelSpoilerGated(channel: Channel?): Boolean =
        StoreStream.getChannelSpoilerAgree().isChannelSpoilerGated(channel)

    /** True when either gate stands between the viewer and the channel's contents. */
    fun isChannelGated(channel: Channel?): Boolean =
        isChannelContentGated(channel) || isChannelSpoilerGated(channel)

    fun isSpoilerGateRequired(channelId: Long?): Boolean {
        channelId ?: return false
        return isChannelSpoilerGated(StoreStream.getChannels().getChannel(channelId))
    }

    fun isSpoilerChannel(channel: Channel?): Boolean = channel?.isSpoilerChannel() == true
}
