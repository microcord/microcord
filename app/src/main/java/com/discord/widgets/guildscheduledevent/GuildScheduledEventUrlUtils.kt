package com.discord.widgets.guildscheduledevent

import com.discord.utilities.rest.ClientBackend

object GuildScheduledEventUrlUtils {
    fun getEventDetailsUrl(guildId: Long, guildEventId: Long): CharSequence =
        "${ClientBackend.active.web}/events/$guildId/$guildEventId"
}
