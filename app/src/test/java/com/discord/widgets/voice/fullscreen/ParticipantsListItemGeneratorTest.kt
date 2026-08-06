package com.discord.widgets.voice.fullscreen

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.sheet.CallParticipantsAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantsListItemGeneratorTest {
    @Test
    fun distantScheduledEventIsNotShown() {
        val rows = rows(event(1L, GuildScheduledEventStatus.SCHEDULED, System.currentTimeMillis() + DAY_MS))

        assertTrue(rows.isEmpty())
    }

    @Test
    fun liveEventWinsOverSoonerScheduledEvent() {
        val now = System.currentTimeMillis()
        val soon = event(1L, GuildScheduledEventStatus.SCHEDULED, now + 60_000L)
        val live = event(2L, GuildScheduledEventStatus.ACTIVE, now + DAY_MS)

        val eventRow = rows(soon, live).single() as CallParticipantsAdapter.ListItem.Event

        assertEquals(live, eventRow.event)
    }

    private fun rows(vararg events: GuildScheduledEvent) =
        ParticipantsListItemGenerator.createConnectedListItems(
            voiceParticipants = emptyMap(),
            mySpectatingStreamKey = null,
            channel = channel(),
            callModel = callModel(events.toList()),
            embeddedActivities = emptyList(),
            applications = emptyMap(),
        )

    private fun callModel(events: List<GuildScheduledEvent>) = CallModel(
        participants = emptyMap(),
        myId = 1L,
        timeConnectedMs = 0L,
        videoDevices = emptyList(),
        selectedVideoDevice = null,
        voiceSettings = StoreMediaSettings.VoiceConfiguration(
            inputMode = MediaEngineConnection.InputMode.VOICE_ACTIVITY,
        ),
        rtcConnectionMetadata = null,
        activeStream = null,
        voiceChannelJoinability = VoiceChannelJoinability.CAN_JOIN,
        guildMaxVideoChannelMembers = GuildMaxVideoChannelUsers.Unlimited,
        guild = null,
        isChannelSelected = false,
        isMyHandRaised = false,
        guildScheduledEvents = events,
        embeddedActivitiesForChannel = emptyMap(),
        applications = emptyMap(),
        channel = channel(),
        numUsersConnected = 0,
        channelPermissions = null,
        audioManagerState = StoreAudioManagerV2.DEFAULT_STATE,
    )

    private fun event(id: Long, status: GuildScheduledEventStatus, startTimeMs: Long) =
        GuildScheduledEvent(
            id = id,
            guildId = 2L,
            channelId = 3L,
            creatorId = null,
            name = "Event",
            description = null,
            image = null,
            scheduledStartTime = UtcDateTime(startTimeMs),
            scheduledEndTime = null,
            privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
            status = status,
            entityType = GuildScheduledEventEntityType.VOICE,
            entityId = null,
            entityMetadata = null,
            skuIds = emptyList(),
            userCount = null,
            userRsvp = null,
            guild = null,
        )

    private fun channel() = Channel(
        topic = null,
        type = Channel.GUILD_VOICE,
        guildId = 2L,
        name = "Voice",
        lastMessageId = 0L,
        id = 3L,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = 0,
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

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
