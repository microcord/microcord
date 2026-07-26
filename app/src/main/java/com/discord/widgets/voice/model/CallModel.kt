package com.discord.widgets.voice.model

import co.discord.media_engine.VideoInputDeviceDescription
import com.discord.api.application.Application
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.permission.Permission
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.models.guild.Guild
import com.discord.rtcconnection.RtcConnection
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.time.Clock
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.utilities.voice.VoiceChannelJoinabilityUtils
import com.discord.widgets.voice.VoiceStoreAccess
import java.util.concurrent.TimeUnit
import rx.Observable

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
        val isPrivate = channel.isPrivate()
        val canCreateInvite = channel.isGuildVocalChannel() && hasPermission(CREATE_INSTANT_INVITE)
        // A stage instance that already has an invite code can be shared even without
        // CREATE_INSTANT_INVITE — the code is the invite.
        val hasStageInviteCode = StoreStream.getStageInstances()
            .getStageInstanceForChannel(channel.id)
            ?.inviteCode != null
        return isPrivate || canCreateInvite || hasStageInviteCode
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
        channel.isStageVoiceChannel() && hasPermission(REQUEST_TO_SPEAK)

    fun getCallDurationMs(clock: Clock) = clock.currentTimeMillis() - timeConnectedMs

    private fun hasPermission(permission: Long) = PermissionUtils.can(permission, channelPermissions)

    companion object {
        private const val CREATE_INSTANT_INVITE = Permission.CREATE_INSTANT_INVITE
        private const val REQUEST_TO_SPEAK = Permission.REQUEST_TO_SPEAK

        /** `MANAGE_EVENTS | SEND_MESSAGES` — the floor for touching any scheduled event. */
        private const val MANAGE_EVENTS_BASE = 0x200000400L
        private const val MANAGE_STAGE_EVENT = Permission.START_STAGE_EVENT
        private const val MANAGE_VOICE_EVENT = Permission.START_VOICE_EVENT

        private const val CALL_FEEDBACK_EXPERIMENT =
            "2019-12_android_call_feedback_sheet_sample_rate_denominator"
        private const val STREAM_FEEDBACK_EXPERIMENT =
            "2020-09_stream_feedback_sheet_sample_rate_denominator"

        /** The reference throttles the participant map because it re-emits per speaking frame. */
        private const val PARTICIPANTS_THROTTLE_MS = 250L

        /**
         * The subset of a [CallModel] that comes from the eight stores which all update together
         * whenever the local connection changes. Kept as its own combine so the outer combine stays
         * within rx's arity limit, exactly as the reference splits it.
         */
        private data class Chunk(
            val channel: Channel,
            val myUserId: Long,
            val timeConnectedMs: Long,
            val voiceConfig: StoreMediaSettings.VoiceConfiguration,
            val voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
            val channelPermissions: Long?,
            val videoDevices: List<VideoInputDeviceDescription>,
            val selectedVideoDevice: VideoInputDeviceDescription?,
            val audioManagerState: StoreAudioManagerV2.State,
        )

        private fun observeChunk(channel: Channel): Observable<Chunk> =
            Observable.combineLatest(
                StoreStream.getUsers().observeMeId(),
                StoreStream.getVoiceChannelSelected().observeTimeSelectedMs(),
                StoreStream.getMediaSettings().getVoiceConfiguration(),
                StoreStream.getVoiceParticipants().get(channel.id)
                    .leadingEdgeThrottle(PARTICIPANTS_THROTTLE_MS, TimeUnit.MILLISECONDS),
                StoreStream.getPermissions().observePermissionsForChannel(channel.id),
                VoiceStoreAccess.observeVideoInputDevices(),
                VoiceStoreAccess.observeSelectedVideoInputDevice(),
                VoiceStoreAccess.observeAudioManagerState(),
            ) { myUserId,
                timeConnectedMs,
                voiceConfig,
                voiceParticipants,
                channelPermissions,
                videoDevices,
                selectedVideoDevice,
                audioManagerState ->
                Chunk(
                    channel = channel,
                    myUserId = myUserId,
                    timeConnectedMs = timeConnectedMs,
                    voiceConfig = voiceConfig,
                    voiceParticipants = voiceParticipants,
                    channelPermissions = channelPermissions,
                    videoDevices = videoDevices,
                    selectedVideoDevice = selectedVideoDevice,
                    audioManagerState = audioManagerState,
                )
            }

        /**
         * Everything the voice UI needs about [channelId], or `null` while the channel is unknown.
         *
         * Emits until unsubscribed; every store feeding it is hot, so a late subscriber gets the
         * current state immediately.
         */
        @JvmStatic
        fun get(channelId: Long): Observable<CallModel?> =
            StoreStream.getChannels().observeChannel(channelId).switchMap<CallModel?> { channel ->
                if (channel == null) {
                    Observable.just(null)
                } else {
                    observeModel(channelId, channel)
                }
            }

        private fun observeModel(channelId: Long, channel: Channel): Observable<CallModel> {
            val experiments = StoreStream.getExperiments()
            return Observable.combineLatest(
                observeChunk(channel),
                StoreStream.getApplicationStreaming().observeActiveStream(),
                VoiceStoreAccess.observeRtcConnectionMetadata(),
                experiments.observeUserExperiment(CALL_FEEDBACK_EXPERIMENT, true)
                    .map { it?.bucket ?: 0 },
                experiments.observeUserExperiment(STREAM_FEEDBACK_EXPERIMENT, true)
                    .map { it?.bucket ?: 0 },
                VoiceChannelJoinabilityUtils.observeJoinability(channelId),
                StoreStream.getGuilds().observeFromChannelId(channelId),
                StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId(),
            ) { chunk,
                activeStream,
                rtcConnectionMetadata,
                callFeedbackTriggerRateDenominator,
                streamFeedbackTriggerRateDenominator,
                voiceChannelJoinability,
                guild,
                selectedVoiceChannelId ->
                create(
                    channel = chunk.channel,
                    guild = guild,
                    myUserId = chunk.myUserId,
                    timeConnectedMs = chunk.timeConnectedMs,
                    voiceConfig = chunk.voiceConfig,
                    voiceParticipants = chunk.voiceParticipants,
                    channelPermissions = chunk.channelPermissions,
                    videoDevices = chunk.videoDevices,
                    isChannelSelected = chunk.channel.id == selectedVoiceChannelId,
                    selectedVideoDevice = chunk.selectedVideoDevice,
                    activeStream = activeStream,
                    rtcConnectionMetadata = rtcConnectionMetadata,
                    callFeedbackTriggerRateDenominator = callFeedbackTriggerRateDenominator,
                    streamFeedbackTriggerRateDenominator = streamFeedbackTriggerRateDenominator,
                    audioManagerState = chunk.audioManagerState,
                    voiceChannelJoinability = voiceChannelJoinability,
                    // The reference additionally combines
                    // `StoreGuildScheduledEvents.observeGuildScheduledEvents(guildId)` filtered to
                    // this channel, `StoreEmbeddedActivities.observeEmbeddedActivitiesForChannel`
                    // and `StoreApplication.observeApplications`. None of those three stores exists
                    // in this reconstruction yet, so the event banner and the embedded-activity
                    // tiles render empty. Wire them here when the stores land.
                    guildScheduledEvents = emptyList(),
                    embeddedActivitiesForChannel = emptyMap(),
                    applications = emptyMap(),
                )
            }
        }

        /**
         * Derives the three fields the stores do not carry directly — the connected-participant
         * count, whether *my* hand is raised, and the guild's video cap — and assembles the model.
         */
        @Suppress("LongParameterList")
        private fun create(
            channel: Channel,
            guild: Guild?,
            myUserId: Long,
            timeConnectedMs: Long,
            voiceConfig: StoreMediaSettings.VoiceConfiguration,
            voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
            channelPermissions: Long?,
            videoDevices: List<VideoInputDeviceDescription>,
            isChannelSelected: Boolean,
            selectedVideoDevice: VideoInputDeviceDescription?,
            activeStream: StoreApplicationStreaming.ActiveApplicationStream?,
            rtcConnectionMetadata: RtcConnection.Metadata?,
            callFeedbackTriggerRateDenominator: Int,
            streamFeedbackTriggerRateDenominator: Int,
            audioManagerState: StoreAudioManagerV2.State,
            voiceChannelJoinability: VoiceChannelJoinability,
            guildScheduledEvents: List<GuildScheduledEvent>,
            embeddedActivitiesForChannel: Map<Long, EmbeddedActivity>,
            applications: Map<Long, Application>,
        ) = CallModel(
            participants = voiceParticipants,
            myId = myUserId,
            timeConnectedMs = timeConnectedMs,
            videoDevices = videoDevices,
            selectedVideoDevice = selectedVideoDevice,
            voiceSettings = voiceConfig,
            rtcConnectionMetadata = rtcConnectionMetadata,
            callFeedbackTriggerRateDenominator = callFeedbackTriggerRateDenominator,
            streamFeedbackTriggerRateDenominator = streamFeedbackTriggerRateDenominator,
            activeStream = activeStream,
            voiceChannelJoinability = voiceChannelJoinability,
            guildMaxVideoChannelMembers = guild?.maxVideoChannelUsers
                ?: GuildMaxVideoChannelUsers.Unlimited,
            guild = guild,
            isChannelSelected = isChannelSelected,
            isMyHandRaised = voiceParticipants[myUserId]?.isRequestingToSpeak == true,
            guildScheduledEvents = guildScheduledEvents,
            embeddedActivitiesForChannel = embeddedActivitiesForChannel,
            applications = applications,
            channel = channel,
            numUsersConnected = voiceParticipants.values.count { it.isConnected },
            channelPermissions = channelPermissions,
            audioManagerState = audioManagerState,
        )
    }
}

enum class CameraState {
    CAMERA_ON,
    CAMERA_OFF,
    CAMERA_DISABLED,
}
