package com.discord.widgets.guildscheduledevent

object GuildScheduledEventUrlUtils {
    fun getEventDetailsUrl(guildId: Long, guildEventId: Long): CharSequence =
        "https://discord.com/events/$guildId/$guildEventId"
}
