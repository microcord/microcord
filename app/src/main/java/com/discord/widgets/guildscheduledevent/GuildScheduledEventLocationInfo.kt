package com.discord.widgets.guildscheduledevent

import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType

sealed class GuildScheduledEventLocationInfo {
    data class ChannelLocation(val channel: Channel?) : GuildScheduledEventLocationInfo()
    data class ExternalLocation(val location: String) : GuildScheduledEventLocationInfo()

    val locationName: String
        get() = when (this) {
            is ChannelLocation -> channel?.name.orEmpty()
            is ExternalLocation -> location
        }

    @get:DrawableRes
    val locationIcon: Int
        get() = when (this) {
            is ChannelLocation -> if (channel?.type == Channel.GUILD_STAGE_VOICE) R.drawable.ic_stage_20dp else R.drawable.ic_channel_voice_16dp
            is ExternalLocation -> R.drawable.ic_location_24dp
        }

    @get:DrawableRes
    val locationIconSmall: Int
        get() = when (this) {
            is ChannelLocation -> if (channel?.type == Channel.GUILD_STAGE_VOICE) R.drawable.ic_stage_20dp else R.drawable.ic_channel_voice_16dp
            is ExternalLocation -> R.drawable.ic_location_16dp
        }

    companion object {
        fun buildLocationInfo(event: GuildScheduledEvent, channel: Channel?): GuildScheduledEventLocationInfo =
            if (event.entityType == GuildScheduledEventEntityType.EXTERNAL) {
                ExternalLocation(event.entityMetadata?.location.orEmpty())
            } else {
                ChannelLocation(channel)
            }

        /** The unsaved-form counterpart, used by the create/edit preview. */
        fun buildLocationInfo(
            guildScheduledEventModel: GuildScheduledEventModel,
            channel: Channel?,
        ): GuildScheduledEventLocationInfo =
            if (guildScheduledEventModel.entityType == GuildScheduledEventEntityType.EXTERNAL) {
                ExternalLocation(guildScheduledEventModel.entityMetadata?.location.orEmpty())
            } else {
                ChannelLocation(channel)
            }
    }
}
