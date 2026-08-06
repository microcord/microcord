package com.discord.widgets.voice.sheet

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.permission.Permission
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.widgets.stage.StageRoles
import com.discord.widgets.voice.model.CallModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetVoiceSettingsBottomSheetViewModelTest {
    @Test
    fun `stage moderator sees stage actions but not invite`() {
        val state = WidgetVoiceSettingsBottomSheetViewModel.toViewState(
            WidgetVoiceSettingsBottomSheetViewModel.StoreState.Valid(
                callModel(Channel.GUILD_STAGE_VOICE),
                StageRoles(isSpeaker = true, isModerator = true),
            ),
        )!!

        assertTrue(state.showStageSettings)
        assertTrue(state.showReportItem)
        assertFalse(state.showInviteItem)
        assertFalse(state.showVoiceParticipants)
        assertTrue(state.noiseCancellationEnabled)
    }

    private fun callModel(channelType: Int) = CallModel(
        participants = emptyMap(),
        myId = 1L,
        timeConnectedMs = 0L,
        videoDevices = emptyList(),
        selectedVideoDevice = null,
        voiceSettings = StoreMediaSettings.VoiceConfiguration(
            inputMode = MediaEngineConnection.InputMode.VOICE_ACTIVITY,
            voiceParticipantsHidden = true,
        ),
        rtcConnectionMetadata = null,
        activeStream = null,
        voiceChannelJoinability = VoiceChannelJoinability.CAN_JOIN,
        guildMaxVideoChannelMembers = GuildMaxVideoChannelUsers.Unlimited,
        guild = null,
        isChannelSelected = false,
        isMyHandRaised = false,
        guildScheduledEvents = emptyList(),
        embeddedActivitiesForChannel = emptyMap(),
        applications = emptyMap(),
        channel = channel(channelType),
        numUsersConnected = 0,
        channelPermissions = Permission.CREATE_INSTANT_INVITE,
        audioManagerState = StoreAudioManagerV2.DEFAULT_STATE,
    )

    private fun channel(type: Int) = Channel(
        topic = null,
        type = type,
        guildId = 2L,
        name = "Stage",
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
