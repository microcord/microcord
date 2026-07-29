@file:JvmName("GuildScheduledEventModelKt")

package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEvent

fun GuildScheduledEvent.toModel(): GuildScheduledEventModel {
    val (startDate, startTime) = GuildScheduledEventPickerDateTime.fromUtcDateTime(scheduledStartTime)
    val end = scheduledEndTime?.let(GuildScheduledEventPickerDateTime::fromUtcDateTime)
    return GuildScheduledEventModel(
        guildId = guildId,
        name = name,
        channelId = channelId,
        creatorId = creatorId,
        startDate = startDate,
        startTime = startTime,
        endDate = end?.first,
        endTime = end?.second,
        description = description,
        entityType = entityType,
        entityMetadata = entityMetadata,
        userCount = userCount,
    )
}
