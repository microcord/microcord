package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityMetadata
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIParams
import com.discord.utilities.time.TimeUtils
import java.io.Serializable

/**
 * The editable form of a guild scheduled event.
 *
 * This is what the create/edit flow carries between its screens (hence [Serializable]): the start
 * and end instants are still split into the date and time the user picked, and [name] is nullable
 * because the form starts empty. Both request bodies return `null` while [name] is unset, which is
 * how "the form is not submittable yet" is expressed.
 */
data class GuildScheduledEventModel(
    val guildId: Long,
    val name: String?,
    val channelId: Long?,
    val creatorId: Long?,
    val startDate: GuildScheduledEventPickerDate,
    val startTime: GuildScheduledEventPickerTime,
    val endDate: GuildScheduledEventPickerDate?,
    val endTime: GuildScheduledEventPickerTime?,
    val description: String?,
    val entityType: GuildScheduledEventEntityType,
    val entityMetadata: GuildScheduledEventEntityMetadata?,
    val userCount: Int?,
    val broadcastToDirectoryChannels: Boolean? = null,
) : Serializable {

    /** `null` until the form has a name. */
    fun toCreateRequestBody(): RestAPIParams.CreateGuildScheduledEventBody? {
        val name = name ?: return null
        val endsAt = if (endDate != null && endTime != null) {
            GuildScheduledEventPickerDateTime.toUtcDateString(endDate, endTime)
        } else {
            null
        }
        return RestAPIParams.CreateGuildScheduledEventBody(
            name = name,
            description = description,
            privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
            scheduledStartTime = GuildScheduledEventPickerDateTime.toUtcDateString(startDate, startTime),
            scheduledEndTime = endsAt,
            channelId = channelId,
            entityType = entityType,
            entityMetadata = entityMetadata,
            broadcastToDirectoryChannels = broadcastToDirectoryChannels,
        )
    }

    /**
     * The PATCH body for [currentEvent].
     *
     * Timestamps are omitted when they did not move, so an edit that only renames the event does
     * not re-send — and re-validate — its schedule. `channelId` and `entityMetadata` are
     * [NullSerializable] because clearing them has to be distinguishable from leaving them alone;
     * an [entityMetadata] with no location is sent as an explicit null.
     */
    fun toUpdateRequestBody(currentEvent: GuildScheduledEvent): RestAPIParams.UpdateGuildScheduledEventBody? {
        val name = name ?: return null
        val startsAtMillis = GuildScheduledEventPickerDateTime.toMillis(startDate, startTime)
        val endsAtMillis = if (endDate != null && endTime != null) {
            GuildScheduledEventPickerDateTime.toMillis(endDate, endTime)
        } else {
            null
        }
        return RestAPIParams.UpdateGuildScheduledEventBody(
            name = name,
            description = description,
            privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
            scheduledStartTime = if (currentEvent.scheduledStartTime.dateTimeMillis == startsAtMillis) {
                null
            } else {
                TimeUtils.toUTCDateTime(startsAtMillis)
            },
            scheduledEndTime = if (currentEvent.scheduledEndTime?.dateTimeMillis == endsAtMillis) {
                null
            } else {
                TimeUtils.toUTCDateTime(endsAtMillis)
            },
            channelId = channelId?.let { NullSerializable.Value(it) } ?: NullSerializable.Null(),
            entityType = entityType,
            // `entityMetadata.location == null` is the reference's `isEmpty` check: a metadata
            // object carrying no location is meaningless and is cleared rather than sent.
            entityMetadata = entityMetadata
                ?.takeIf { it.location != null }
                ?.let { NullSerializable.Value(it) }
                ?: NullSerializable.Null(),
            status = null,
            broadcastToDirectoryChannels = broadcastToDirectoryChannels,
        )
    }
}
