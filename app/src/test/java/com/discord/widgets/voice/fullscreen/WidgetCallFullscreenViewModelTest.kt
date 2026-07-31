package com.discord.widgets.voice.fullscreen

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.permission.Permission
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.widgets.voice.controls.VoiceControlsOutputSelectorState
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.model.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCallFullscreenViewModelTest {
    @Test
    fun validViewStateDerivesReferenceMenuTitleAndControlVisibility() {
        val state = viewState(channel = channel(Channel.GUILD_VOICE), idle = true)

        assertEquals("Voice", state.titleText)
        assertEquals(
            setOf(
                WidgetCallFullscreenViewModel.MenuItem.VOICE_SETTINGS,
                WidgetCallFullscreenViewModel.MenuItem.CHANNEL_SETTINGS,
                WidgetCallFullscreenViewModel.MenuItem.TEXT_IN_VOICE,
                WidgetCallFullscreenViewModel.MenuItem.SHOW_PARTICIPANT_LIST,
            ),
            state.menuItems,
        )
        assertFalse(state.isVideoCallGridVisible)
        assertTrue(state.showFooter)
        assertTrue(state.showHeader)
        assertEquals("grid", state.analyticsVideoLayout)
    }

    @Test
    fun stageStateSuppressesTitleAndNonStageMenuItems() {
        val state = viewState(channel = channel(Channel.GUILD_STAGE_VOICE), idle = false)

        assertNull(state.titleText)
        assertEquals(
            setOf(
                WidgetCallFullscreenViewModel.MenuItem.VOICE_SETTINGS,
                WidgetCallFullscreenViewModel.MenuItem.CHANNEL_SETTINGS,
            ),
            state.menuItems,
        )
        assertNull(state.analyticsVideoLayout)
    }

    @Test
    fun nestedContractOrderAndEventPayloadsMatchReference() {
        assertEquals(
            listOf("GRID", "STAGE", "PRIVATE_CALL_PARTICIPANTS"),
            WidgetCallFullscreenViewModel.DisplayMode.values().map { it.name },
        )
        assertEquals(
            listOf(
                "LAUNCH_OVERLAY",
                "CHANNEL_SETTINGS",
                "VOICE_SETTINGS",
                "SWITCH_CAMERA",
                "SHOW_PARTICIPANT_LIST",
                "TEXT_IN_VOICE",
            ),
            WidgetCallFullscreenViewModel.MenuItem.values().map { it.name },
        )
        assertEquals(
            WidgetCallFullscreenViewModel.Event.EnqueueCallFeedbackSheet(1L, "rtc", "media", 2L, 3),
            WidgetCallFullscreenViewModel.Event.EnqueueCallFeedbackSheet(1L, "rtc", "media", 2L, 3),
        )
    }

    private fun viewState(
        channel: Channel,
        idle: Boolean,
    ) = WidgetCallFullscreenViewModel.ViewState.Valid(
        callModel = callModel(channel),
        stageCallModel = null,
        overlayStatus = WidgetCallFullscreenViewModel.OverlayStatus.ENABLED,
        visibleVideoParticipants = emptyList(),
        isIdleFooter = idle,
        isIdleHeader = idle,
        isControlFadingDisabled = false,
        isSwitchCameraButtonVisible = false,
        cameraState = CameraState.CAMERA_OFF,
        displayMode = if (channel.type == Channel.GUILD_STAGE_VOICE) {
            WidgetCallFullscreenViewModel.DisplayMode.STAGE
        } else WidgetCallFullscreenViewModel.DisplayMode.GRID,
        outputSelectorState = VoiceControlsOutputSelectorState.SPEAKER_OFF,
        focusedParticipantKey = null,
        isAnyoneUsingVideo = false,
        pipParticipant = null,
        noiseCancellation = true,
        privateCallUserListItems = emptyList(),
        showParticipantsHiddenView = false,
        lockCallScreenControls = false,
        startedAsVideo = false,
        perceptualStreamVolume = 100f,
        totalMentionsCount = 0,
        showLowConnectivityBar = false,
        stageRoles = null,
        isUpdatingRequestToSpeak = false,
        isMovingToAudience = false,
        requestingToSpeakCount = 0,
        stopOffscreenVideo = false,
        channelPermissions = Permission.MANAGE_CHANNELS,
        startableEvent = null,
        activeEvent = null,
        textInVoiceMentionsCount = 0,
        textInVoiceUnreadsCount = 0,
        isTextInVoiceEnabled = true,
        isTextInVoiceChannelSelected = false,
        allVideoParticipants = emptyMap(),
    )

    private fun callModel(channel: Channel) = CallModel(
        participants = emptyMap(),
        myId = 1L,
        timeConnectedMs = 0L,
        videoDevices = emptyList(),
        selectedVideoDevice = null,
        voiceSettings = StoreMediaSettings.VoiceConfiguration(
            inputMode = MediaEngineConnection.InputMode.VOICE_ACTIVITY,
        ),
        rtcConnectionMetadata = null,
        callFeedbackTriggerRateDenominator = 0,
        streamFeedbackTriggerRateDenominator = 0,
        activeStream = null,
        voiceChannelJoinability = VoiceChannelJoinability.CAN_JOIN,
        guildMaxVideoChannelMembers = GuildMaxVideoChannelUsers.Unlimited,
        guild = null,
        isChannelSelected = true,
        isMyHandRaised = false,
        guildScheduledEvents = emptyList(),
        embeddedActivitiesForChannel = emptyMap(),
        applications = emptyMap(),
        channel = channel,
        numUsersConnected = 0,
        channelPermissions = Permission.MANAGE_CHANNELS,
        audioManagerState = StoreAudioManagerV2.DEFAULT_STATE,
    )

    private fun channel(type: Int) = Channel(
        topic = null,
        type = type,
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
}
