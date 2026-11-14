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
 * - [activeStreams] — every stream this client is broadcasting or watching, with its connection
 *   state, keyed by stream key. Several can be watched at once, as on desktop; at most one is ours.
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

    /** Insertion-ordered, so the last entry is the stream most recently asked for. */
    private val activeStreams = LinkedHashMap<String, ActiveApplicationStream>()
    private var activeStreamsSnapshot: Map<String, ActiveApplicationStream> = emptyMap()
    private var activeApplicationStreamSnapshot: ActiveApplicationStream? = null

    /**
     * The one stream for callers that only show one: our own broadcast while there is one, else
     * the stream watched most recently.
     */
    private val activeApplicationStream: ActiveApplicationStream?
        get() = activeStreams.values.firstOrNull(::isOwn) ?: activeStreams.values.lastOrNull()

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

    private fun isOwn(stream: ActiveApplicationStream): Boolean =
        stream.stream.ownerId == userStore.getMeInternal().id

    @StoreThread
    private fun updateActiveStreams(update: MutableMap<String, ActiveApplicationStream>.() -> Unit) {
        val wasScreenSharing = isScreenSharing()
        activeStreams.update()
        val isScreenSharing = isScreenSharing()
        if (wasScreenSharing != isScreenSharing) onIsScreenSharingChanged(isScreenSharing)
        markChanged()
    }

    @StoreThread
    private fun putActiveStream(streamKey: String, state: ActiveApplicationStream.State) =
        updateActiveStreams {
            this[streamKey] = ActiveApplicationStream(state, ModelApplicationStream.decodeStreamKey(streamKey))
        }

    /** Streams only exist inside their voice channel, so leaving one ends every stream in it. */
    @StoreThread
    private fun dropStreamsOutside(channelId: Long?) {
        if (activeStreams.values.any { it.stream.channelId != channelId }) {
            updateActiveStreams { values.removeAll { it.stream.channelId != channelId } }
        }
    }

    @StoreThread
    private fun handleStreamCreateOrUpdate(streamKey: String, paused: Boolean, viewerIds: List<Long>) {
        val state =
            if (paused) ActiveApplicationStream.State.PAUSED else ActiveApplicationStream.State.ACTIVE
        putActiveStream(streamKey, state)
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
            if (activeStreams[stream.encodedStreamKey]?.state?.isStreamActive() == true) return@schedule

            // Only one broadcast at a time: replacing ours has to tear the old one down first or
            // the backend keeps both alive. Streams we are watching are unaffected.
            activeStreams.values.firstOrNull { isOwn(it) && it.state.isStreamActive() }
                ?.let { stopStreamInternal(it.stream.encodedStreamKey) }
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
        activeStreams.values
            .filter { it.state != ActiveApplicationStream.State.ENDED }
            .forEach { targetStream(it.stream.encodedStreamKey, force = true, keepOthers = true) }
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
    fun handleStreamCreateRequest(streamKey: String) =
        putActiveStream(streamKey, ActiveApplicationStream.State.CONNECTING)

    /** Local echo of our own opcode-20 send. */
    @StoreThread
    fun handleStreamWatch(streamKey: String) =
        putActiveStream(streamKey, ActiveApplicationStream.State.CONNECTING)

    /**
     * Applies `STREAM_DELETE`.
     *
     * The reason decides what the user sees:
     * - `STREAM_FULL` -> [ActiveApplicationStream.State.DENIED_FULL]; the rejection is for a stream
     *   we just asked to watch
     * - `unavailable` -> [ActiveApplicationStream.State.RECONNECTING]; the server is moving us
     * - `USER_REQUESTED` -> gone, we asked for it
     * - anything else -> [ActiveApplicationStream.State.ENDED], which keeps the "stream ended" card
     */
    @StoreThread
    fun handleStreamDelete(streamDelete: StreamDelete) {
        streamSpectators.remove(streamDelete.streamKey)
        val key = streamDelete.streamKey
        if (streamDelete.deleteReason == StreamDelete.Reason.STREAM_FULL) {
            putActiveStream(key, ActiveApplicationStream.State.DENIED_FULL)
            return
        }

        val affected = activeStreams[key] ?: return
        updateActiveStreams {
            when {
                streamDelete.unavailable ->
                    this[key] = affected.copy(state = ActiveApplicationStream.State.RECONNECTING)
                streamDelete.deleteReason == StreamDelete.Reason.USER_REQUESTED -> remove(key)
                else -> this[key] = affected.copy(state = ActiveApplicationStream.State.ENDED)
            }
        }
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
        if (activeStreams[streamKey]?.state?.isStreamActive() == true) return

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
        targetStream = null
        if (parked != null && channelId == parked.channelId) streamWatchRequest(parked.encodedStreamKey)
        // Streams in the newly selected channel are kept; the rest go, including on leaving voice.
        dropStreamsOutside(channelId)
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

        if (userId == userStore.getMeInternal().id) dropStreamsOutside(voiceState.channelId)

        val stream = streamsByUser[userId]
        if (isStreaming && stream != null &&
            activeStreams[stream.encodedStreamKey]?.state == ActiveApplicationStream.State.ENDED
        ) {
            handleStreamTargeted(stream.encodedStreamKey)
        }
    }

    @StoreThread
    fun isScreenSharing(): Boolean = activeStreams.values.any(::isOwn)

    @StoreThread
    fun isUserStreaming(userId: Long): Boolean {
        addBreadCrumb("Asking if $userId is streaming")
        return streamsByUser.containsKey(userId)
    }

    fun observeActiveStream(): Observable<ActiveApplicationStream?> =
        observationDeck.connectRx(this) { activeApplicationStreamSnapshot }

    /** Every stream being broadcast or watched, keyed by stream key. */
    fun observeActiveStreams(): Observable<Map<String, ActiveApplicationStream>> =
        observationDeck.connectRx(this) { activeStreamsSnapshot }.distinctUntilChanged()

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
     * Asks to watch [streamKey].
     *
     * @param force replays the request even when we are already on that stream; used after a
     *   reconnect, where the local state says "watching" but the server has forgotten us.
     * @param keepOthers watches it alongside the streams already being watched, as desktop does;
     *   otherwise they are stopped first. Our own broadcast is never stopped here.
     */
    @JvmOverloads
    fun targetStream(streamKey: String, force: Boolean = false, keepOthers: Boolean = false) = dispatcher.schedule {
        val alreadyOnIt = activeStreams[streamKey]?.state?.isStreamActive() == true
        if (!force && alreadyOnIt) return@schedule
        if (alreadyOnIt) stopStreamInternal(streamKey)
        if (!keepOthers) {
            activeStreams.values
                .filter { !isOwn(it) && it.stream.encodedStreamKey != streamKey && it.state.isStreamActive() }
                .forEach { stopStreamInternal(it.stream.encodedStreamKey) }
        }
        targetStreamRequest?.invoke(streamKey) ?: handleStreamTargeted(streamKey)
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        addBreadCrumb("Snapshotting 'streamsByUser' of size, ${streamsByUser.size}")
        try {
            streamsByUserSnapshot = HashMap(streamsByUser)
            activeStreamsSnapshot = LinkedHashMap(activeStreams)
            activeApplicationStreamSnapshot = activeApplicationStream
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
