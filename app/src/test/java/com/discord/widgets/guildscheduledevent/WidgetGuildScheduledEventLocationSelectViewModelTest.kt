package com.discord.widgets.guildscheduledevent

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityMetadata
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.guild.Guild
import com.discord.widgets.channels.WidgetChannelSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGuildScheduledEventLocationSelectViewModelTest {
    private val voice = channel(1L, Channel.GUILD_VOICE, "Voice")
    private val stage = channel(2L, Channel.GUILD_STAGE_VOICE, "Stage")
    private val text = channel(3L, Channel.GUILD_TEXT, "Text")

    @Test
    fun createStartsUnselectedAndFiltersCreatableChannels() {
        val state = reduce(
            guild = Guild(id = 10L, features = setOf(GuildFeature.COMMUNITY)),
            permissions = mapOf(voice.id to Long.MAX_VALUE, stage.id to Long.MAX_VALUE, text.id to Long.MAX_VALUE),
        )

        assertEquals(GuildScheduledEventEntityType.NONE, state.selectedLocationOption)
        assertNull(state.selectedChannel)
        assertEquals(setOf(voice.id), state.availableVoiceChannelIds)
        assertEquals(setOf(stage.id), state.availableStageChannelIds)
        assertEquals(setOf(voice.id, stage.id), state.availableChannels.keys)
        assertTrue(state.canCreateExternalEvent)
        assertTrue(state.showStageOptionIfUnavailable)
        assertTrue(state.canChangeChannel)
    }

    @Test
    fun initialChannelSelectsItsExactMode() {
        val state = reduce(initialChannelId = stage.id)

        assertEquals(GuildScheduledEventEntityType.STAGE_INSTANCE, state.selectedLocationOption)
        assertEquals(stage, state.selectedChannel)
    }

    @Test
    fun storeRefreshPreservesExternalInputAndSelection() {
        val edited = reduce().copy(
            selectedLocationOption = GuildScheduledEventEntityType.EXTERNAL,
            selectedChannel = null,
            externalLocation = "Town Hall",
        )

        val refreshed = reduce(current = edited)

        assertEquals(GuildScheduledEventEntityType.EXTERNAL, refreshed.selectedLocationOption)
        assertEquals("Town Hall", refreshed.externalLocation)
        assertNull(refreshed.selectedChannel)
    }

    @Test
    fun liveExternalEditRestoresLocationAndLocksMode() {
        val event = event(GuildScheduledEventStatus.ACTIVE)
        val state = reduce(existingEventId = event.id, existingEvent = event)

        assertEquals(GuildScheduledEventEntityType.EXTERNAL, state.selectedLocationOption)
        assertEquals("Main Hall", state.externalLocation)
        assertFalse(state.canChangeChannel)
    }

    @Test
    fun scheduledEditCanChangeMode() {
        val event = event(GuildScheduledEventStatus.SCHEDULED)

        assertTrue(reduce(existingEventId = event.id, existingEvent = event).canChangeChannel)
    }

    @Test
    fun externalModeRequiresGuildManageEventsPermission() {
        val event = event(GuildScheduledEventStatus.SCHEDULED)
        val state = reduce(
            existingEventId = event.id,
            existingEvent = event,
            guildPermissions = 0L,
        )

        assertEquals(GuildScheduledEventEntityType.NONE, state.selectedLocationOption)
        assertFalse(state.canCreateExternalEvent)
    }

    @Test
    fun selectorAdapterKeepsOnlyAllowedLocationIds() {
        val items = WidgetChannelSelector.Model.buildItems(
            channels = listOf(voice, stage),
            includeNoChannel = false,
            filterFunction = WidgetChannelSelector.SetFilterFunction(setOf(stage.id)),
        )

        assertEquals(listOf(stage.id), items.mapNotNull { it.channel?.id })
    }

    @Test
    fun nextValidationMatchesEachLocationMode() {
        val base = reduce()

        assertFalse(WidgetGuildScheduledEventLocationSelect.isNextEnabled(base))
        assertFalse(
            WidgetGuildScheduledEventLocationSelect.isNextEnabled(
                base.copy(selectedLocationOption = GuildScheduledEventEntityType.VOICE),
            ),
        )
        assertTrue(
            WidgetGuildScheduledEventLocationSelect.isNextEnabled(
                base.copy(
                    selectedLocationOption = GuildScheduledEventEntityType.STAGE_INSTANCE,
                    selectedChannel = stage,
                ),
            ),
        )
        assertFalse(
            WidgetGuildScheduledEventLocationSelect.isNextEnabled(
                base.copy(selectedLocationOption = GuildScheduledEventEntityType.EXTERNAL),
            ),
        )
        assertTrue(
            WidgetGuildScheduledEventLocationSelect.isNextEnabled(
                base.copy(
                    selectedLocationOption = GuildScheduledEventEntityType.EXTERNAL,
                    externalLocation = " ",
                ),
            ),
        )
    }

    @Test
    fun settingsResultsDistinguishFinishedBackAndQuit() {
        val actions = mutableListOf<String>()
        val handle: (Int) -> Unit = { result ->
            WidgetGuildScheduledEventSettings.handleResult(
                result,
                onFinished = { actions += "finished" },
                onQuit = { actions += "quit" },
            )
        }

        handle(-1)
        handle(0)
        handle(2)

        assertEquals(listOf("finished", "quit"), actions)
    }

    private fun reduce(
        initialChannelId: Long? = null,
        existingEventId: Long? = null,
        guild: Guild = Guild(id = 10L),
        existingEvent: GuildScheduledEvent? = null,
        guildPermissions: Long? = Long.MAX_VALUE,
        permissions: Map<Long, Long> = mapOf(voice.id to Long.MAX_VALUE, stage.id to Long.MAX_VALUE),
        current: WidgetGuildScheduledEventLocationSelectViewModel.ViewState.Valid? = null,
    ) = WidgetGuildScheduledEventLocationSelectViewModel.reduce(
        initialChannelId,
        existingEventId,
        WidgetGuildScheduledEventLocationSelectViewModel.StoreState(
            guild = guild,
            channels = listOf(voice, stage, text).associateBy(Channel::id),
            channelPermissions = permissions,
            guildPermissions = guildPermissions,
            existingGuildScheduledEvent = existingEvent,
        ),
        current,
    )

    private fun event(status: GuildScheduledEventStatus) = GuildScheduledEvent(
        id = 20L,
        guildId = 10L,
        channelId = null,
        creatorId = null,
        name = "Event",
        description = null,
        image = null,
        scheduledStartTime = UtcDateTime(0L),
        scheduledEndTime = null,
        privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
        status = status,
        entityType = GuildScheduledEventEntityType.EXTERNAL,
        entityId = null,
        entityMetadata = GuildScheduledEventEntityMetadata("Main Hall"),
        skuIds = emptyList(),
        userCount = null,
        userRsvp = null,
        guild = null,
    )

    private fun channel(id: Long, type: Int, name: String) = Channel(
        topic = null,
        type = type,
        guildId = 10L,
        name = name,
        lastMessageId = 0L,
        id = id,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = id.toInt(),
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = false,
        parentId = 0L,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
