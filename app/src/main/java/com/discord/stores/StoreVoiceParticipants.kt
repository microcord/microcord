package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreMediaSettings.VoiceConfiguration
import com.discord.stores.StoreVideoStreams.UserStreams
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.user.UserUtils.getDisplayName
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Joins ten independent sources into the participant map a voice surface renders.
 *
 * This store owns no state of its own beyond the selected participant; it is a projection. That
 * matters because the inputs update at wildly different rates: speaking flags arrive several times a
 * second and guild member data can churn during a member chunk, so both are throttled on the leading
 * edge before they reach the combine. The result is recomputed on the computation scheduler and
 * de-duplicated, so a tile only re-binds when something it displays actually changed.
 *
 * The me-user is built separately from everyone else because two of its fields come from local state
 * rather than the gateway: see [VoiceUser.isSelfMuted] / [VoiceUser.isSelfDeafened].
 */
class StoreVoiceParticipants(
    private val channelsStore: StoreChannels,
    private val usersStore: StoreUser,
    private val guildsStore: StoreGuilds,
    private val voiceStatesStore: StoreVoiceStates,
    private val voiceSpeakingStore: StoreVoiceSpeaking,
    private val callsStore: StoreCalls,
    private val videoStreamsStore: StoreVideoStreams,
    private val applicationStreamingStore: StoreApplicationStreaming,
    private val mediaSettingsStore: StoreMediaSettings,
    /**
     * `StoreVoiceChannelSelected.observeSelectedVoiceChannelId()`. Injected because that store does
     * not exist yet; the only thing needed here is "the selection changed, drop the focused tile".
     */
    private val observeSelectedVoiceChannelId: () -> Observable<Long> = { Observable.empty() },
    /**
     * `StreamContextService.getForUser(userId, isForVoiceCall = true)`. Injected for the same
     * reason; without it participants simply carry no stream context and the go-live overlay stays
     * hidden.
     */
    private val streamContextForUser: (userId: Long) -> Observable<StreamContext?> = {
        Observable.just(null)
    },
) : Store() {

    private val selectedParticipantSubject = BehaviorSubject.create<Long?>(NO_SELECTED_PARTICIPANT)

    /**
     * One participant tile.
     *
     * Most fields are derived in the constructor rather than by the caller so every surface agrees
     * on what "muted" means. The two derivations worth knowing:
     *
     * - [isSpeaking] is suppressed while the user is *server* muted or deafened. The media engine
     *   still reports audio energy for a server-muted user, and without this a moderator-muted
     *   participant would pulse as if they were being heard.
     * - [isSelfMuted] / [isSelfDeafened] read the local [VoiceConfiguration] when [isMe]. Our own
     *   voice state is only echoed back after the gateway round-trips it, so trusting it would make
     *   our own mute button lag by a network hop — and never light up at all while disconnected.
     */
    data class VoiceUser(
        val user: User,
        val voiceState: VoiceState?,
        val isRinging: Boolean,
        val streams: UserStreams?,
        val isMe: Boolean,
        val guildMember: GuildMember?,
        /** Encoded key of the stream this user is watching, if any. */
        val watchingStream: String?,
        val streamContext: StreamContext?,
        val isBooster: Boolean,
        private val voiceConfiguration: VoiceConfiguration?,
        private val _isSpeaking: Boolean,
    ) {
        val isSpeaking: Boolean =
            voiceState != null && _isSpeaking && !voiceState.mute && !voiceState.deaf
        val nickname: String? = guildMember?.nick
        val callStreamId: Int? = streams?.callStreamId
        val applicationStreamId: Int? = streams?.applicationStreamId
        /** The stream this user is broadcasting. */
        val applicationStream: ModelApplicationStream? = streamContext?.stream

        val isServerMuted: Boolean = voiceState?.mute == true
        val isSelfMuted: Boolean =
            if (isMe) voiceConfiguration?.isSelfMuted() == true else voiceState?.selfMute == true
        val isMuted: Boolean = isServerMuted || isSelfMuted

        val isServerDeafened: Boolean = voiceState?.deaf == true
        val isSelfDeafened: Boolean =
            if (isMe) voiceConfiguration?.isSelfDeafened == true else voiceState?.selfDeaf == true
        val isDeafened: Boolean = isServerDeafened || isSelfDeafened

        val isRequestingToSpeak: Boolean = voiceState.stageRequestToSpeakState.isRequestingToSpeak
        val isInvitedToSpeak: Boolean =
            voiceState.stageRequestToSpeakState ==
                StageRequestToSpeakState.REQUESTED_TO_SPEAK_AND_AWAITING_USER_ACK

        val displayName: String get() = user.getDisplayName(nickname)
        val isConnected: Boolean get() = voiceState != null
    }

    private fun create(
        meUser: MeUser,
        otherUsers: Collection<User>,
        voiceStates: Map<Long, VoiceState>,
        speakingUsers: Set<Long>,
        ringingUsers: Collection<Long>,
        videoStreams: Map<Long, UserStreams?>,
        guildMembers: Map<Long, GuildMember>,
        streamSpectators: Map<String, List<Long>>,
        voiceConfiguration: VoiceConfiguration,
        streamContexts: Map<Long, StreamContext?>,
    ): Map<Long, VoiceUser> {
        fun watchedStreamKey(userId: Long): String? =
            streamSpectators.entries.firstOrNull { userId in it.value }?.key

        val participants = LinkedHashMap<Long, VoiceUser>()
        participants[meUser.id] = VoiceUser(
            user = meUser,
            voiceState = voiceStates[meUser.id],
            isRinging = ringingUsers.contains(meUser.id),
            streams = videoStreams[meUser.id],
            isMe = true,
            guildMember = guildMembers[meUser.id],
            watchingStream = watchedStreamKey(meUser.id),
            // The reference deliberately passes no stream context for the me-user: our own go-live
            // state is rendered from StoreApplicationStreaming, not from a spectator context.
            streamContext = null,
            isBooster = guildMembers[meUser.id]?.premiumSince != null,
            voiceConfiguration = voiceConfiguration,
            _isSpeaking = speakingUsers.contains(meUser.id),
        )
        otherUsers.forEach { user ->
            participants[user.id] = VoiceUser(
                user = user,
                voiceState = voiceStates[user.id],
                isRinging = ringingUsers.contains(user.id),
                streams = videoStreams[user.id],
                isMe = false,
                guildMember = guildMembers[user.id],
                watchingStream = watchedStreamKey(user.id),
                streamContext = streamContexts[user.id],
                isBooster = guildMembers[user.id]?.premiumSince != null,
                voiceConfiguration = voiceConfiguration,
                _isSpeaking = speakingUsers.contains(user.id),
            )
        }
        return participants
    }

    /**
     * Everyone except us who could appear in [channel].
     *
     * A private call shows every recipient whether or not they joined, so the ringing state has a
     * row to render on. A guild voice channel shows only members with a voice state, resolved
     * through the guild's computed member map so nicknames and boost state come along.
     */
    private fun getOtherVoiceUsers(
        channel: Channel,
        voiceStates: Map<Long, VoiceState>,
    ): Observable<Collection<User>> = when {
        channel.isPrivate ->
            Observable.just<Collection<User>>(channel.recipients.orEmpty().map { CoreUser(it) })
        channel.isGuildVocal -> usersStore.observeMeId().switchMap { meId ->
            guildsStore.observeComputed(channel.guildId)
                .map { members -> members.keys }
                .map { memberIds ->
                    memberIds.filter { it != meId && voiceStates.containsKey(it) }
                }
                .switchMap<Collection<User>> { otherMemberIds ->
                    usersStore.observeUsers(otherMemberIds).map { it.values }
                }
        }
        else -> Observable.just<Collection<User>>(emptyList())
    }

    private fun getStreamContextsForUsers(userIds: List<Long>): Observable<Map<Long, StreamContext?>> {
        if (userIds.isEmpty()) return Observable.just(emptyMap())
        return Observable.combineLatest(userIds.map(streamContextForUser)) { contexts ->
            userIds.withIndex().associate { (index, userId) ->
                userId to contexts[index] as StreamContext?
            }
        }
    }

    fun get(channelId: Long): Observable<Map<Long, VoiceUser>> =
        channelsStore.observeChannel(channelId)
            .switchMap<Map<Long, VoiceUser>> { channel ->
                if (channel == null) return@switchMap Observable.just(emptyMap<Long, VoiceUser>())
                val guildId = if (channel.isPrivate) StoreVoiceStates.PRIVATE_CHANNELS_GUILD_ID else channel.guildId
                voiceStatesStore.observe(guildId, channel.id).switchMap { voiceStates ->
                    Observable.combineLatest(
                        usersStore.observeMe(),
                        getOtherVoiceUsers(channel, voiceStates),
                    ) { meUser, otherUsers -> meUser to otherUsers }
                        .switchMap { (meUser, otherUsers) ->
                            val otherUserIds = otherUsers.map(User::id)
                            Observable.combineLatest(
                                voiceSpeakingStore.observeSpeakingUsers()
                                    .leadingEdgeThrottle(SPEAKING_THROTTLE_MS, TimeUnit.MILLISECONDS),
                                callsStore.get(channelId).map { it?.ringingUserIds.orEmpty() },
                                videoStreamsStore.observeUserStreams(),
                                guildsStore.observeComputed(guildId)
                                    .leadingEdgeThrottle(MEMBER_THROTTLE_SECONDS, TimeUnit.SECONDS),
                                applicationStreamingStore.observeStreamSpectators(),
                                mediaSettingsStore.getVoiceConfig(),
                                getStreamContextsForUsers(otherUserIds + meUser.id),
                            ) { speakingUsers, ringingUsers, videoStreams, guildMembers,
                                streamSpectators, voiceConfig, streamContexts ->
                                create(
                                    meUser,
                                    otherUsers,
                                    voiceStates,
                                    speakingUsers,
                                    ringingUsers,
                                    videoStreams,
                                    guildMembers,
                                    streamSpectators,
                                    voiceConfig,
                                    streamContexts,
                                )
                            }
                        }
                }
            }
            .computationLatest()
            .distinctUntilChanged()

    /** The participant whose tile is focused, or [NO_SELECTED_PARTICIPANT] when the grid is shown. */
    fun getStream(): Observable<Long?> = selectedParticipantSubject.distinctUntilChanged()

    override fun init(context: Context) {
        super.init(context)
        // Changing channel must drop the focused tile: the selected user is almost never present in
        // the channel we just moved to, and a stale focus renders an empty full-screen view.
        observeSelectedVoiceChannelId().subscribe({ selectParticipant(null) }, {})
    }

    fun selectParticipant(userId: Long?) = selectedParticipantSubject.onNext(userId)

    companion object {
        /** The reference seeds the subject with `0L`, which no user id can collide with. */
        const val NO_SELECTED_PARTICIPANT = 0L

        private const val SPEAKING_THROTTLE_MS = 250L
        private const val MEMBER_THROTTLE_SECONDS = 1L

        private val Channel.isPrivate: Boolean
            get() = type == Channel.DM || type == Channel.GROUP_DM

        private val Channel.isGuildVocal: Boolean
            get() = type == Channel.GUILD_VOICE || type == Channel.GUILD_STAGE_VOICE
    }
}
