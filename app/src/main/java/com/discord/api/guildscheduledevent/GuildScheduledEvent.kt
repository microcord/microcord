package com.discord.api.guildscheduledevent

import com.discord.api.guild.Guild
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import java.io.Serializable

enum class GuildScheduledEventStatus(val apiValue: Int) {
    SCHEDULED(1), ACTIVE(2), COMPLETED(3), CANCELED(4), UNKNOWN(0),
}

enum class GuildScheduledEventEntityType(val apiValue: Int) {
    NONE(0), STAGE_INSTANCE(1), VOICE(2), EXTERNAL(3), UNKNOWN(-1),
}

data class GuildScheduledEventEntityMetadata(val location: String? = null) : Serializable

data class GuildScheduledEventMeUser(
    val userId: Long,
    val guildScheduledEventId: Long,
    val user: User?,
    val guildScheduledEvent: GuildScheduledEvent?,
)

data class GuildScheduledEvent(
    val id: Long,
    val guildId: Long,
    val channelId: Long?,
    val creatorId: Long?,
    val name: String,
    val description: String?,
    val image: String?,
    val scheduledStartTime: UtcDateTime,
    val scheduledEndTime: UtcDateTime?,
    val privacyLevel: StageInstancePrivacyLevel,
    val status: GuildScheduledEventStatus,
    val entityType: GuildScheduledEventEntityType,
    val entityId: Long?,
    val entityMetadata: GuildScheduledEventEntityMetadata?,
    val skuIds: List<Long>,
    val userCount: Int?,
    val userRsvp: GuildScheduledEventMeUser?,
    val guild: Guild?,
) {
    fun isInChannel(channelId: Long) = this.channelId == null || this.channelId == channelId
}
