package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.stores.utilities.Default
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GuildScheduledEventFlowTest {
    @Test
    fun activeGuildProjectionOnlyIncludesGuildsWithLiveEvents() {
        assertEquals(
            setOf(1L, 3L),
            GuildIdsWithActiveEventsModel.activeGuildIds(
                mapOf(
                    1L to listOf(event(11L, 1L, GuildScheduledEventStatus.ACTIVE)),
                    2L to listOf(event(22L, 2L, GuildScheduledEventStatus.SCHEDULED)),
                    3L to listOf(
                        event(31L, 3L, GuildScheduledEventStatus.COMPLETED),
                        event(32L, 3L, GuildScheduledEventStatus.ACTIVE),
                    ),
                ),
            ),
        )
    }

    @Test
    fun directoryAssociationStartsUnrequested() {
        assertSame(Default, GuildScheduledEventDirectoryAssociationState().enabledAsync)
    }

    @Test
    fun directoryDetailsResolveTheRequestedEvent() {
        val first = event(11L, 1L, GuildScheduledEventStatus.SCHEDULED)
        val requested = event(22L, 2L, GuildScheduledEventStatus.ACTIVE)

        assertSame(
            requested,
            GuildScheduledEventDetailsViewModel.findDirectoryEvent(
                listOf(directoryEntry(first), directoryEntry(requested)),
                requested.id,
            ),
        )
    }

    @Test
    fun serverEventMapsToEditableModel() {
        val model = event(22L, 2L, GuildScheduledEventStatus.SCHEDULED).toModel()

        assertEquals(2L, model.guildId)
        assertEquals("Event", model.name)
        assertEquals(GuildScheduledEventEntityType.EXTERNAL, model.entityType)
    }

    private fun directoryEntry(event: GuildScheduledEvent) = DirectoryEntryEvent(
        directoryChannelId = 7L,
        entityId = event.id,
        authorId = 8L,
        description = "",
        primaryCategoryId = 0,
        createdAt = UtcDateTime(0),
        guildScheduledEvent = event,
    )

    private fun event(id: Long, guildId: Long, status: GuildScheduledEventStatus) = GuildScheduledEvent(
        id = id,
        guildId = guildId,
        channelId = null,
        creatorId = null,
        name = "Event",
        description = null,
        image = null,
        scheduledStartTime = UtcDateTime(0),
        scheduledEndTime = null,
        privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
        status = status,
        entityType = GuildScheduledEventEntityType.EXTERNAL,
        entityId = null,
        entityMetadata = null,
        skuIds = emptyList(),
        userCount = null,
        userRsvp = null,
        guild = null,
    )
}
