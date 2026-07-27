package com.discord.stores

import android.util.Log
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.StreamCreateOrUpdate
import com.discord.models.domain.StreamDelete
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Who is screen sharing, who is watching, and the state of *our* stream.
 *
 * Three separate things live here because they change together:
 * - [streamsByUser] — derived purely from voice states (`self_stream`), so it is correct for every
 *   user in every channel we can see, not just the one we are in.
 * - [streamSpectators] — from `STREAM_CREATE`/`STREAM_UPDATE`, only for streams we participate in.
 * - [activeApplicationStream] — the single stream this client is broadcasting *or* watching, with
 *   its connection state. There is only ever one.
 *
 * [targetStream] handles the "watch a stream in a channel I am not in yet" flow: the request is
 * parked until the voice channel selection catches up, then replayed.
 */
class StoreApplicationStreaming(
    private val dispatcher: Dispatcher,
    private val userStore: StoreUser,
    /** `StoreVoiceChannelSelected.getSelectedVoiceChannelId()`; that store does not exist yet. */
    private val getSelectedVoiceChannelId: () -> Long = { 0L },
    /** `StoreGatewayConnection.streamCreate` (opcode 18). */
    private val streamCreateRequest: (streamKey: String, preferredRegion: String?) -> Unit = { _, _ -> },
    /** `StoreGatewayConnection.streamWatch` (opcode 20). */
    private val streamWatchRequest: (streamKey: String) -> Unit = {},
    /** Routes a local stop back through the normal `STREAM_DELETE` path. */
    private val streamDeleteRequest: (StreamDelete) -> Unit = {},
    /** Routes a user-selected stream through StoreStream so voice joins its channel before watching. */
    private val targetStreamRequest: ((streamKey: String) -> Unit)? = null,
    /** Lets the RTC layer know whether we are the one sharing. */
    private val onIsScreenSharingChanged: (Boolean) -> Unit = {},
    /**
     * `guildId to channelId` of the live RTC connection, or `null` when there is none. A stream can
     * only be started on the channel the connection is already carrying.
     */
    private val getRtcConnectedChannel: () -> Pair<Long?, Long>? = { null },
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    data class ActiveApplicationStream(
        val state: State,
        val stream: ModelApplicationStream,
    ) {
        enum class State {
            CONNECTING,
            ACTIVE,
            RECONNECTING,
            ENDED,
            PAUSED,
            DENIED_FULL;

            /**
             * A paused stream is still *active*: the streamer minimised the shared window, the RTC
             * connection is intact, and the viewer keeps their slot. `ENDED` and `DENIED_FULL` are
             * the only terminal states.
             */
            fun isStreamActive() = this in ACTIVE_STATES

            companion object {
                val ACTIVE_STATES = listOf(CONNECTING, ACTIVE, RECONNECTING, PAUSED)
            }
        }
    }

    /** Highest concurrent viewer count seen per stream; the streamer's summary reports it. */
    private class StreamViewerTracker {
        private val maxViewersByStream = HashMap<String, Int>()

        fun clear() = maxViewersByStream.clear()

        fun getMaxViewers(streamKey: String): Int? = maxViewersByStream[streamKey]

        fun onStreamUpdated(streamKey: String, viewerCount: Int) {
            maxViewersByStream[streamKey] = maxOf(maxViewersByStream[streamKey] ?: 0, viewerCount)
        }

        fun remove(streamKey: String) {
            maxViewersByStream.remove(streamKey)
        }
    }

    private var activeApplicationStream: ActiveApplicationStream? = null
    private var targetStream: ModelApplicationStream? = null

    private val streamsByUser = HashMap<Long, ModelApplicationStream>()
    private var streamsByUserSnapshot: Map<Long, ModelApplicationStream> = emptyMap()

    private val streamSpectators = HashMap<String, List<Long>>()
    private var streamSpectatorsSnapshot: Map<String, List<Long>> = emptyMap()

    private val streamViewerTracker = StreamViewerTracker()

    /**
     * Rolling log of the last [MAX_BREAD_CRUMBS] mutations, attached to a crash from [snapshotData].
     *
     * The reference added this to chase a `ConcurrentModificationException` while snapshotting
     * [streamsByUser]; the breadcrumbs are what identify the off-thread writer.
     */
    private val breadCrumbs = ArrayDeque<String>()

    init {
        addBreadCrumb("Initializing the store.")
    }

    @Synchronized
    private fun addBreadCrumb(message: String) {
        breadCrumbs.addLast("$message, on thread: ${Thread.currentThread()}")
        if (breadCrumbs.size > MAX_BREAD_CRUMBS) breadCrumbs.removeFirst()
    }

    @Synchronized
    private fun dumpBreadcrumbs() = breadCrumbs.forEach { Log.w(LOG_TAG, it) }

    private fun isScreenSharing(activeApplicationStream: ActiveApplicationStream?): Boolean =
        activeApplicationStream?.stream?.ownerId == userStore.getMeInternal().id

    @StoreThread
    private fun updateActiveApplicationStream(activeApplicationStream: ActiveApplicationStream?) {
        val wasScreenSharing = isScreenSharing(this.activeApplicationStream)
        val isScreenSharing = isScreenSharing(activeApplicationStream)
        if (wasScreenSharing != isScreenSharing) onIsScreenSharingChanged(isScreenSharing)
        this.activeApplicationStream = activeApplicationStream
    }

    @StoreThread
    private fun handleStreamCreateOrUpdate(streamKey: String, paused: Boolean, viewerIds: List<Long>) {
        val state =
            if (paused) ActiveApplicationStream.State.PAUSED else ActiveApplicationStream.State.ACTIVE
        updateActiveApplicationStream(
            ActiveApplicationStream(state, ModelApplicationStream.decodeStreamKey(streamKey)),
        )
        streamSpectators[streamKey] = viewerIds
        streamViewerTracker.onStreamUpdated(streamKey, viewerIds.size)
        markChanged()
    }

    private fun stopStreamInternal(streamKey: String) =
        streamDeleteRequest(StreamDelete(streamKey, StreamDelete.Reason.USER_REQUESTED, false))

    /**
     * Starts sharing our screen in [channelId].
     *
     * Refuses unless the RTC connection is already on exactly that channel/guild — the stream rides
     * the existing voice connection, and asking the gateway for one we cannot carry leaves a
     * phantom stream server-side.
     */
    @JvmOverloads
    fun createStream(channelId: Long, guildId: Long?, preferredRegion: String? = null) =
        dispatcher.schedule {
            val connected = getRtcConnectedChannel()
            if (connected == null || connected.second != channelId || connected.first != guildId) {
                Log.e(
                    LOG_TAG,
                    "Failed to start stream: RTC connection is on ${connected?.second} " +
                        "(guild ${connected?.first}), requested $channelId (guild $guildId)",
                )
                return@schedule
            }
            val ownerId = userStore.getMeInternal().id
            val stream = if (guildId != null) {
                ModelApplicationStream.GuildStream(guildId, channelId, ownerId)
            } else {
                ModelApplicationStream.CallStream(channelId, ownerId)
            }
            val active = activeApplicationStream
            val alreadyStreamingThis =
                active?.stream?.encodedStreamKey == stream.encodedStreamKey &&
                    active?.state?.isStreamActive() == true
            if (alreadyStreamingThis) return@schedule

            // Only one stream at a time: replacing an active one has to tear the old one down first
            // or the backend keeps both alive and the viewer sees the stale feed.
            if (active?.state?.isStreamActive() == true) {
                stopStreamInternal(active.stream.encodedStreamKey)
            }
            streamCreateRequest(stream.encodedStreamKey, preferredRegion)
        }

    @StoreThread
    internal fun getActiveApplicationStreamKeyInternal(): String? =
        activeApplicationStream?.stream?.encodedStreamKey

    @StoreThread
    fun getMaxViewersForStream(userId: Long, channelId: Long, guildId: Long?): Int? {
        val stream = if (guildId != null) {
            ModelApplicationStream.GuildStream(guildId, channelId, userId)
        } else {
            ModelApplicationStream.CallStream(channelId, userId)
        }
        return streamViewerTracker.getMaxViewers(stream.encodedStreamKey)
    }

    fun getStreamsByUser(): Map<Long, ModelApplicationStream> = streamsByUserSnapshot

    /**
     * Re-derives every stream from READY and re-targets our own so a reconnect resumes watching.
     */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        addBreadCrumb("Connection open, clearing streams by user.")
        streamsByUser.clear()
        streamViewerTracker.clear()
        activeApplicationStream
            ?.takeIf { it.state != ActiveApplicationStream.State.ENDED }
            ?.let { targetStream(it.stream.encodedStreamKey, force = true) }
        payload.guilds?.forEach { guild ->
            guild.voiceStates?.forEach { handleVoiceStateUpdate(it, guild.id) }
        }
        markChanged()
    }

    @StoreThread
    fun handleStreamCreate(streamCreate: StreamCreateOrUpdate) {
        // A fresh stream starts a fresh peak-viewer count.
        streamViewerTracker.remove(streamCreate.streamKey)
        handleStreamCreateOrUpdate(streamCreate.streamKey, streamCreate.paused, streamCreate.viewers)
    }

    @StoreThread
    fun handleStreamUpdate(streamUpdate: StreamCreateOrUpdate) =
        handleStreamCreateOrUpdate(streamUpdate.streamKey, streamUpdate.paused, streamUpdate.viewers)

    /** Local echo of our own opcode-18 send, so the UI shows "connecting" before the server replies. */
    @StoreThread
    fun handleStreamCreateRequest(streamKey: String) {
        updateActiveApplicationStream(
            ActiveApplicationStream(
                ActiveApplicationStream.State.CONNECTING,
                ModelApplicationStream.decodeStreamKey(streamKey),
            ),
        )
        markChanged()
    }

    /** Local echo of our own opcode-20 send. */
    @StoreThread
    fun handleStreamWatch(streamKey: String) {
        updateActiveApplicationStream(
            ActiveApplicationStream(
                ActiveApplicationStream.State.CONNECTING,
                ModelApplicationStream.decodeStreamKey(streamKey),
            ),
        )
        markChanged()
    }

    /**
     * Applies `STREAM_DELETE`.
     *
     * The reason decides what the user sees:
     * - `STREAM_FULL` -> [ActiveApplicationStream.State.DENIED_FULL], regardless of what we were on,
     *   because the rejection is for the stream we just asked to watch
     * - `unavailable` -> [ActiveApplicationStream.State.RECONNECTING]; the server is moving us
     * - `USER_REQUESTED` -> gone, we asked for it
     * - anything else -> [ActiveApplicationStream.State.ENDED], which keeps the "stream ended" card
     */
    @StoreThread
    fun handleStreamDelete(streamDelete: StreamDelete) {
        streamSpectators.remove(streamDelete.streamKey)
        if (streamDelete.deleteReason == StreamDelete.Reason.STREAM_FULL) {
            updateActiveApplicationStream(
                ActiveApplicationStream(
                    ActiveApplicationStream.State.DENIED_FULL,
                    ModelApplicationStream.decodeStreamKey(streamDelete.streamKey),
                ),
            )
            markChanged()
            return
        }

        val affected = activeApplicationStream?.stream ?: targetStream
        if (affected?.encodedStreamKey != streamDelete.streamKey) return

        val next = when {
            streamDelete.unavailable ->
                activeApplicationStream?.copy(state = ActiveApplicationStream.State.RECONNECTING)
            streamDelete.deleteReason == StreamDelete.Reason.USER_REQUESTED -> null
            else -> activeApplicationStream?.copy(state = ActiveApplicationStream.State.ENDED)
        }
        updateActiveApplicationStream(next)
        markChanged()
    }

    /**
     * Watch [streamKey], or park it until we are in its channel.
     *
     * Parking matters because "join to watch" is a two-step flow: the user taps a go-live badge in a
     * channel they are not connected to, voice connects, and only then can the watch be sent.
     */
    @StoreThread
    fun handleStreamTargeted(streamKey: String) {
        val stream = ModelApplicationStream.decodeStreamKey(streamKey)
        val active = activeApplicationStream
        if (stream == active?.stream && active.state.isStreamActive()) return

        if (getSelectedVoiceChannelId() == stream.channelId) {
            streamWatchRequest(stream.encodedStreamKey)
            targetStream = null
        } else {
            targetStream = stream
        }
    }

    @StoreThread
    fun handleVoiceChannelSelected(channelId: Long) {
        val parked = targetStream
        if (parked != null && channelId == parked.channelId) {
            streamWatchRequest(parked.encodedStreamKey)
            targetStream = null
            return
        }
        // Staying in the stream's channel keeps it; anything else drops it, including leaving voice.
        if (activeApplicationStream?.stream?.channelId == channelId) return
        updateActiveApplicationStream(null)
        targetStream = null
        markChanged()
    }

    /**
     * Maintains [streamsByUser] from `self_stream`.
     *
     * Also self-heals two cases the server does not tell us about directly: our own stream dying
     * because we moved channel, and a previously ENDED stream coming back — the latter re-targets so
     * a viewer resumes automatically.
     */
    @StoreThread
    @JvmOverloads
    fun handleVoiceStateUpdate(voiceState: VoiceState, guildId: Long = voiceState.guildId) {
        addBreadCrumb("Handling a voice state update for ${voiceState.userId}")
        val channelId = voiceState.channelId
        val userId = voiceState.userId
        val isStreaming = voiceState.selfStream
        val hasGuild = guildId != 0L
        val hasChannel = channelId != null && channelId != 0L

        when {
            isStreaming && hasGuild && hasChannel -> {
                streamsByUser[userId] = ModelApplicationStream.GuildStream(guildId, channelId!!, userId)
                markChanged()
            }
            isStreaming && hasChannel -> {
                streamsByUser[userId] = ModelApplicationStream.CallStream(channelId!!, userId)
                markChanged()
            }
            streamsByUser.remove(userId) != null -> markChanged()
        }

        val active = activeApplicationStream
        if (active != null && userId == userStore.getMeInternal().id &&
            active.stream.channelId != voiceState.channelId
        ) {
            updateActiveApplicationStream(null)
            markChanged()
        }

        val stream = streamsByUser[userId]
        if (isStreaming && stream != null && active != null &&
            stream.encodedStreamKey == active.stream.encodedStreamKey &&
            active.state == ActiveApplicationStream.State.ENDED
        ) {
            handleStreamTargeted(stream.encodedStreamKey)
        }
    }

    @StoreThread
    fun isScreenSharing(): Boolean = isScreenSharing(activeApplicationStream)

    @StoreThread
    fun isUserStreaming(userId: Long): Boolean {
        addBreadCrumb("Asking if $userId is streaming")
        return streamsByUser.containsKey(userId)
    }

    fun observeActiveStream(): Observable<ActiveApplicationStream?> =
        observationDeck.connectRx(this) { activeApplicationStream }

    fun observeStreamSpectators(): Observable<Map<String, List<Long>>> =
        observationDeck.connectRx(this) { streamSpectatorsSnapshot }.distinctUntilChanged()

    fun observeStreamsByUser(): Observable<Map<Long, ModelApplicationStream>> =
        observationDeck.connectRx(this) { getStreamsByUser() }.distinctUntilChanged()

    fun observeStreamsForGuild(guildId: Long): Observable<Map<Long, ModelApplicationStream>> =
        observationDeck.connectRx(this) {
            streamsByUserSnapshot.filterValues {
                it is ModelApplicationStream.GuildStream && it.guildId == guildId
            }
        }.distinctUntilChanged()

    fun observeStreamsForUser(userId: Long): Observable<ModelApplicationStream?> =
        observationDeck.connectRx(this) { streamsByUserSnapshot[userId] }.distinctUntilChanged()

    fun stopStream(streamKey: String) = dispatcher.schedule { stopStreamInternal(streamKey) }

    /**
     * Asks to watch [streamKey], stopping whatever we are on first.
     *
     * @param force replays the request even when we are already on that stream; used after a
     *   reconnect, where the local state says "watching" but the server has forgotten us.
     */
    @JvmOverloads
    fun targetStream(streamKey: String, force: Boolean = false) = dispatcher.schedule {
        val active = activeApplicationStream
        val alreadyOnIt =
            active?.stream?.encodedStreamKey == streamKey && active?.state?.isStreamActive() == true
        if (!force && alreadyOnIt) return@schedule
        if (active?.state?.isStreamActive() == true) {
            stopStreamInternal(active.stream.encodedStreamKey)
        }
        targetStreamRequest?.invoke(streamKey) ?: handleStreamTargeted(streamKey)
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        addBreadCrumb("Snapshotting 'streamsByUser' of size, ${streamsByUser.size}")
        try {
            streamsByUserSnapshot = HashMap(streamsByUser)
            streamSpectatorsSnapshot = HashMap(streamSpectators)
        } catch (failure: Exception) {
            dumpBreadcrumbs()
            throw failure
        }
    }

    private companion object {
        private const val MAX_BREAD_CRUMBS = 50
        private const val LOG_TAG = "StoreAppStreaming"
    }
}
