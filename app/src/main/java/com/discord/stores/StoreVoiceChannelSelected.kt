package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.shouldPlayJoinLeaveSounds
import com.discord.api.permission.Permission
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.time.Clock
import com.discord.utilities.voice.VoiceChannelJoinability
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Which voice channel this client is in.
 *
 * This is the *intent*, not the connection: it is set before the RTC connection exists and is what
 * drives connecting, so it has to survive a gateway session that has not started yet
 * ([preselectedVoiceChannelId]) and has to be corrected by the server's own view of where we are
 * ([handleVoiceStateUpdates]) — being moved or disconnected by a moderator arrives that way and
 * would otherwise leave the client pretending it is still connected.
 */
class StoreVoiceChannelSelected(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    private val channels: StoreChannels,
    private val voiceStates: StoreVoiceStates,
    private val permissions: StorePermissions,
    private val getMeId: () -> Long,
    /** Join/leave/move feedback. `utilities/media/AppSoundManager` is not reconstructed yet. */
    private val playSound: (Sound) -> Unit,
    /**
     * Mute the microphone before joining a stage channel — a stage audience member joins silent.
     * The reference calls `StoreMediaEngine.setAudioInputEnabled(false)`.
     */
    private val setAudioInputEnabled: (Boolean) -> Unit,
    /** `guild.maxVideoChannelUsers`, or `null` for unlimited. */
    private val getMaxVideoChannelUsers: (guildId: Long) -> Int? = { null },
) : StoreV2() {

    enum class Sound { JOINED, LEFT, MOVED }

    enum class JoinVoiceChannelResult {
        SUCCESS,
        DEFERRED_UNTIL_SESSION_START,
        ALREADY_CONNECTED,
        FAILED_PERMISSIONS_MISSING,
        FAILED_GUILD_VIDEO_AT_CAPACITY,
        FAILED_CHANNEL_FULL,
        FAILED_CHANNEL_DOES_NOT_EXIST,
    }

    private var selectedVoiceChannelId: Long = VOICE_CHANNEL_ID_NONE
    private var lastSelectedVoiceChannelId: Long = VOICE_CHANNEL_ID_NONE
    private var preselectedVoiceChannelId: Long? = null
    private var sessionId: String? = null

    /**
     * When the current selection was published. Re-stamped on every dispatch this store changes in,
     * which is what `StoreReadStates` uses to ignore voice-channel messages older than the join.
     */
    private var timeSelectedMs: Long = 0L

    // -- Reads ----------------------------------------------------------------------------------

    fun getSelectedVoiceChannelId(): Long = selectedVoiceChannelId
    fun getLastSelectedVoiceChannelId(): Long = lastSelectedVoiceChannelId
    fun getTimeSelectedMs(): Long = timeSelectedMs
    fun getSelectedVoiceChannel(): Channel? = channels.findChannelById(selectedVoiceChannelId)

    fun observeSelectedVoiceChannelId(): Observable<Long> =
        observationDeck.connectRx(this) { selectedVoiceChannelId }

    fun observeTimeSelectedMs(): Observable<Long> =
        observationDeck.connectRx(this) { timeSelectedMs }

    fun observeSelectedChannel(): Observable<Channel?> =
        observationDeck.connectRx(this, channels) { channels.findChannelById(selectedVoiceChannelId) }

    // -- Commands -------------------------------------------------------------------------------

    /**
     * Ask to join [channelId], or leave with [VOICE_CHANNEL_ID_NONE].
     *
     * The result is delivered asynchronously because joinability can only be evaluated on the store
     * thread, against the channels and permissions of that moment.
     */
    fun selectVoiceChannel(channelId: Long): Observable<JoinVoiceChannelResult> {
        val channel = channels.getChannel(channelId)
        if (channel != null && channel.isStageVoiceChannel()) setAudioInputEnabled(false)
        val resultSubject = PublishSubject.create<JoinVoiceChannelResult>()
        dispatcher.schedule {
            resultSubject.onNext(selectVoiceChannelInternal(channelId, forceMoved = false))
            resultSubject.onCompleted()
        }
        return resultSubject
    }

    fun clear() = dispatcher.schedule { clearInternal() }

    // -- Handlers -------------------------------------------------------------------------------

    @StoreThread
    fun handleAuthToken(authToken: String?) {
        if (authToken == null) clearInternal()
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
        preselectedVoiceChannelId?.let { selectVoiceChannelInternal(it, forceMoved = false) }
    }

    @StoreThread
    fun handleChannelOrThreadCreateOrUpdate() = validateSelectedVoiceChannel()

    @StoreThread
    fun handleChannelOrThreadDelete() = validateSelectedVoiceChannel()

    @StoreThread
    fun handleGuildRemove() = validateSelectedVoiceChannel()

    @StoreThread
    fun handleGuildRoleCreateOrUpdate() = validateSelectedVoiceChannel()

    @StoreThread
    fun handleGuildRoleRemove() = validateSelectedVoiceChannel()

    @StoreThread
    fun handleGuildMemberAdd(userId: Long) {
        if (userId == getMeId()) validateSelectedVoiceChannel()
    }

    /** A stream key names the channel it originates in; targeting one implies joining that channel. */
    @StoreThread
    fun handleStreamTargeted(channelId: Long) {
        selectVoiceChannelInternal(channelId, forceMoved = false)
    }

    /**
     * Reconcile with the server's view of our own voice state.
     *
     * Only our own state, in the guild we believe we are connected to, and only for our own gateway
     * session: a state from a different session means another client took over the connection, so
     * this one must let go.
     */
    @StoreThread
    fun handleVoiceStateUpdates(voiceState: VoiceState) {
        if (getMeId() != voiceState.userId) return
        val current = channels.findChannelByIdInternal(selectedVoiceChannelId) ?: return
        if (current.guildId != voiceState.guildId) return
        val newChannelId = voiceState.channelId
        val sameSession = voiceState.sessionId == sessionId
        if (newChannelId != selectedVoiceChannelId && sameSession) {
            if (newChannelId != null) {
                selectVoiceChannelInternal(newChannelId, forceMoved = true)
                playSound(Sound.MOVED)
            } else {
                clearInternal()
            }
        } else if (!sameSession) {
            clearInternal()
        }
    }

    @StoreThread
    override fun snapshotData() {
        timeSelectedMs = if (selectedVoiceChannelId > 0L) clock.currentTimeMillis() else 0L
    }

    // -- Internals -------------------------------------------------------------------------------

    @StoreThread
    private fun clearInternal() {
        val channel = channels.findChannelByIdInternal(selectedVoiceChannelId)
        if (channel != null && channel.shouldPlayJoinLeaveSounds()) playSound(Sound.LEFT)
        selectVoiceChannelInternal(VOICE_CHANNEL_ID_NONE, forceMoved = false)
    }

    /**
     * @param forceMoved the server moved us; the join is applied without re-checking joinability or
     *   replaying the join sound.
     */
    @StoreThread
    private fun selectVoiceChannelInternal(channelId: Long, forceMoved: Boolean): JoinVoiceChannelResult {
        if (sessionId == null) {
            // No gateway session yet: remember the intent and replay it from handleConnectionOpen.
            preselectedVoiceChannelId = channelId
            return JoinVoiceChannelResult.DEFERRED_UNTIL_SESSION_START
        }
        preselectedVoiceChannelId = null
        if (channelId == selectedVoiceChannelId) return JoinVoiceChannelResult.ALREADY_CONNECTED

        val joinability = getJoinability(channelId)
        val canJoin = when (joinability) {
            VoiceChannelJoinability.CAN_JOIN -> true
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY,
            VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST,
            -> false
            // A moderator move overrides a missing permission or a full channel.
            VoiceChannelJoinability.PERMISSIONS_MISSING,
            VoiceChannelJoinability.CHANNEL_FULL,
            -> forceMoved
        }

        val previous = selectedVoiceChannelId
        if (canJoin) {
            if (!forceMoved) {
                val channel = channels.findChannelByIdInternal(channelId)
                if (channel != null && channel.shouldPlayJoinLeaveSounds()) playSound(Sound.JOINED)
            }
            selectedVoiceChannelId = channelId
            if (channelId > 0L) lastSelectedVoiceChannelId = channelId
        } else {
            selectedVoiceChannelId = VOICE_CHANNEL_ID_NONE
        }
        if (previous != selectedVoiceChannelId) markChanged()

        return if (canJoin) {
            JoinVoiceChannelResult.SUCCESS
        } else {
            when (joinability) {
                VoiceChannelJoinability.CAN_JOIN -> JoinVoiceChannelResult.SUCCESS
                VoiceChannelJoinability.PERMISSIONS_MISSING -> JoinVoiceChannelResult.FAILED_PERMISSIONS_MISSING
                VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY -> JoinVoiceChannelResult.FAILED_GUILD_VIDEO_AT_CAPACITY
                VoiceChannelJoinability.CHANNEL_FULL -> JoinVoiceChannelResult.FAILED_CHANNEL_FULL
                VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST -> JoinVoiceChannelResult.FAILED_CHANNEL_DOES_NOT_EXIST
            }
        }
    }

    /** Drop the selection when the channel it points at stops existing. */
    @StoreThread
    private fun validateSelectedVoiceChannel() {
        if (selectedVoiceChannelId == VOICE_CHANNEL_ID_NONE) return
        val previous = selectedVoiceChannelId
        if (channels.findChannelByIdInternal(selectedVoiceChannelId) == null) clearInternal()
        if (selectedVoiceChannelId != previous) markChanged()
    }

    /**
     * Whether [channelId] can be joined right now.
     *
     * Leaving (`channelId == 0`) is always allowed. The reference routes this through
     * `VoiceChannelJoinabilityUtils`, which also consults the channel's stage instance; that helper
     * lives in `utilities/voice` and has not been reconstructed, so the stage-instance gate is not
     * applied here yet.
     */
    @StoreThread
    private fun getJoinability(channelId: Long): VoiceChannelJoinability {
        if (channelId == VOICE_CHANNEL_ID_NONE) return VoiceChannelJoinability.CAN_JOIN
        val channel = channels.findChannelByIdInternal(channelId)
            ?: return VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST
        val channelPermissions = permissions.getPermissionsByChannelInternal()[channelId]
        if (!PermissionUtils.hasAccess(channel, channelPermissions)) {
            return VoiceChannelJoinability.PERMISSIONS_MISSING
        }
        val occupants = voiceStates.getInternal()[channel.guildId].orEmpty()
            .count { it.value.channelId == channelId }
        val maxVideoChannelUsers = getMaxVideoChannelUsers(channel.guildId)
        if (maxVideoChannelUsers != null && occupants >= maxVideoChannelUsers) {
            return VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY
        }
        // MOVE_MEMBERS is the reference's bypass for a full channel.
        val bypassesUserLimit = PermissionUtils.can(Permission.MOVE_MEMBERS, channelPermissions)
        if (channel.userLimit > 0 && occupants >= channel.userLimit && !bypassesUserLimit) {
            return VoiceChannelJoinability.CHANNEL_FULL
        }
        return VoiceChannelJoinability.CAN_JOIN
    }

    companion object {
        const val VOICE_CHANNEL_ID_NONE = 0L
    }
}
