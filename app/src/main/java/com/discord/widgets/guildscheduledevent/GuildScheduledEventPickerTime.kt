package com.discord.widgets.guildscheduledevent

import java.io.Serializable

data class GuildScheduledEventPickerTime(
    val hourOfDay: Int,
    val minute: Int,
    val second: Int,
) : Serializable
