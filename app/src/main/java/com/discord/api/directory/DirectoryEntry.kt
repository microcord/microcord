package com.discord.api.directory

import com.discord.api.guild.preview.GuildPreview
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.utcdatetime.UtcDateTime

/** What a directory entry points at. */
enum class DirectoryEntryType(val key: Int) {
    Guild(0),
    GuildScheduledEvent(1),
}

/** A server listed in a hub directory channel. */
data class DirectoryEntryGuild(
    val directoryChannelId: Long,
    val entityId: Long,
    val authorId: Long,
    val description: String,
    val primaryCategoryId: Int,
    val guild: GuildPreview,
    val createdAt: UtcDateTime,
)

/** A scheduled event listed in a hub directory channel. */
data class DirectoryEntryEvent(
    val directoryChannelId: Long,
    val entityId: Long,
    val authorId: Long,
    val description: String,
    val primaryCategoryId: Int,
    val createdAt: UtcDateTime,
    val guildScheduledEvent: GuildScheduledEvent,
)
