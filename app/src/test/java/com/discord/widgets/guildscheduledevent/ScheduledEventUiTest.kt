package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEventEntityMetadata
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledEventUiTest {
    @Test
    fun splitContractsPreserveReferenceValues() {
        assertEquals(
            listOf(GuildScheduledEventDetailsSource.Guild, GuildScheduledEventDetailsSource.Directory),
            GuildScheduledEventDetailsSource.entries,
        )
        assertEquals(
            listOf(EventDetailsSection.EVENT_INFO, EventDetailsSection.RSVP_LIST),
            EventDetailsSection.entries,
        )
        assertEquals(
            listOf(
                EventDetailsRsvpUsersFetchState.LOADING,
                EventDetailsRsvpUsersFetchState.ERROR,
                EventDetailsRsvpUsersFetchState.EMPTY,
                EventDetailsRsvpUsersFetchState.SUCCESS,
            ),
            EventDetailsRsvpUsersFetchState.entries,
        )
        assertEquals(GuildScheduledEventDetailsSource.Guild, GuildScheduledEventDetailsArgs(1L).source)
        assertEquals(
            "https://discord.com/events/123/456",
            GuildScheduledEventUrlUtils.getEventDetailsUrl(123L, 456L),
        )
    }

    @Test
    fun timingPreservesReferenceBoundaries() {
        val now = 1_000_000_000L
        assertEquals(
            GuildScheduledEventTiming.LIVE,
            ScheduledEventUi.timing(now, GuildScheduledEventStatus.ACTIVE, now),
        )
        assertEquals(
            GuildScheduledEventTiming.EXPIRED,
            ScheduledEventUi.timing(now - 60 * 60_000L, GuildScheduledEventStatus.SCHEDULED, now),
        )
        assertEquals(
            GuildScheduledEventTiming.NOW,
            ScheduledEventUi.timing(now, GuildScheduledEventStatus.SCHEDULED, now),
        )
        assertEquals(
            GuildScheduledEventTiming.SOON,
            ScheduledEventUi.timing(now + 15 * 60_000L, GuildScheduledEventStatus.SCHEDULED, now),
        )
    }

    @Test
    fun externalEventFormProducesACompleteCreateRequest() {
        val model = GuildScheduledEventModel(
            guildId = 1L,
            name = "Town hall",
            channelId = null,
            creatorId = null,
            startDate = GuildScheduledEventPickerDate(2026, 6, 27),
            startTime = GuildScheduledEventPickerTime(18, 0, 0),
            endDate = GuildScheduledEventPickerDate(2026, 6, 27),
            endTime = GuildScheduledEventPickerTime(19, 0, 0),
            description = "Weekly update",
            entityType = GuildScheduledEventEntityType.EXTERNAL,
            entityMetadata = GuildScheduledEventEntityMetadata("Community hall"),
            userCount = 1,
        )

        val request = requireNotNull(model.toCreateRequestBody())
        assertEquals("Town hall", request.name)
        assertEquals("Community hall", request.entityMetadata?.location)
        requireNotNull(request.scheduledEndTime)
    }
}
