package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEventBroadcast
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class GuildScheduledEventDirectoryAssociationState(
    val enabledAsync: RestCallState<GuildScheduledEventBroadcast> = Default,
)
