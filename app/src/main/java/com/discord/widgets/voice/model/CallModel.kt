package com.discord.widgets.voice.model

import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.api.application.Application
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.models.guild.Guild
import com.discord.rtcconnection.RtcConnection
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.time.Clock
import com.discord.utilities.voice.VoiceChannelJoinability

data class CallModel(
    val participants: Map<Long, StoreVoiceParticipants.VoiceUser>,
    val myId: Long,
    val timeConnectedMs: Long,
    val videoDevices: List<VideoInputDeviceDescription>,
    val selectedVideoDevice: VideoInputDeviceDescription?,
    val voiceSettings: StoreMediaSettings.VoiceConfiguration,
    val rtcConnectionMetadata: RtcConnection.Metadata?,
    val callFeedbackTriggerRateDenominator: Int,
    val streamFeedbackTriggerRateDenominator: Int,
    val activeStream: StoreApplicationStreaming.ActiveApplicationStream?,
    val voiceChannelJoinability: VoiceChannelJoinability,
    val guildMaxVideoChannelMembers: GuildMaxVideoChannelUsers,
    val guild: Guild?,
    val isChannelSelected: Boolean,
    val isMyHandRaised: Boolean,
    val guildScheduledEvents: List<GuildScheduledEvent>,
    val embeddedActivitiesForChannel: Map<Long, EmbeddedActivity>,
    val applications: Map<Long, Application>,
    val channel: Channel,
    val numUsersConnected: Int,
    val channelPermissions: Long?,
    val audioManagerState: StoreAudioManagerV2.State,
) {
    val cameraState = if (selectedVideoDevice == null) CameraState.CAMERA_OFF else CameraState.CAMERA_ON
    val isVideoCall = isStreaming || participants.values.any { it.voiceState?.selfVideo == true }
    val inputMode: MediaEngineConnection.InputMode get() = voiceSettings.inputMode
    val dmRecipient: StoreVoiceParticipants.VoiceUser?
        get() = participants.values.firstOrNull { channel.type == Channel.DM && it.user.id != myId }
    val isConnected get() = participants[myId]?.isConnected == true
    val isMuted get() = participants[myId]?.voiceState?.mute == true
    val isSelfDeafened get() = voiceSettings.isSelfDeafened
    val isServerDeafened get() = participants[myId]?.voiceState?.deaf == true
    val isDeafenedByAnySource get() = isSelfDeafened || isServerDeafened
    val isMeMutedByAnySource: Boolean
        get() = participants[myId]?.voiceState?.let {
            it.selfMute || it.selfDeaf || it.mute || it.deaf
        } == true
    val isStreaming get() = activeStream?.stream?.ownerId == myId
    val isSuppressed get() = participants[myId]?.voiceState?.suppress == true

    fun canInvite(): Boolean {
        val isPrivate = channel.type == Channel.DM || channel.type == Channel.GROUP_DM
        val canCreateInvite = channel.type in setOf(Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE) &&
            hasPermission(CREATE_INSTANT_INVITE)
        val hasStageTopic = StoreStream.getStageInstances()
            .getStageInstanceForChannel(channel.id)
            ?.topic != null
        return isPrivate || canCreateInvite || hasStageTopic
    }

    fun canManageEvent(): Boolean {
        if (!hasPermission(MANAGE_EVENTS_BASE)) return false
        return when (channel.type) {
            Channel.GUILD_STAGE_VOICE -> hasPermission(MANAGE_STAGE_EVENT)
            Channel.GUILD_VOICE -> hasPermission(MANAGE_VOICE_EVENT)
            else -> false
        }
    }

    fun canRequestToSpeak() =
        channel.type == Channel.GUILD_STAGE_VOICE && hasPermission(REQUEST_TO_SPEAK)

    fun getCallDurationMs(clock: Clock) = clock.currentTimeMillis() - timeConnectedMs

    private fun hasPermission(permission: Long) = channelPermissions?.and(permission) == permission

    private companion object {
        const val CREATE_INSTANT_INVITE = 1L
        const val REQUEST_TO_SPEAK = 1L shl 32
        const val MANAGE_EVENTS_BASE = 0x200000400L
        const val MANAGE_STAGE_EVENT = 0x201400010L
        const val MANAGE_VOICE_EVENT = 0x200100400L
    }
}

enum class CameraState {
    CAMERA_ON,
    CAMERA_OFF,
    CAMERA_DISABLED,
}
